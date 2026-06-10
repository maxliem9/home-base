package com.homebase.security

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*

/**
 * In-memory, per-source throttle for `POST /auth/login` (issue #8).
 *
 * Both usernames are effectively public (seeded from `SEED_USERS`), so the password is the only
 * hurdle and the hub is reachable over the public internet — an unbounded login endpoint invites
 * online brute force. bcrypt's per-attempt cost slows a single guess; this caps the *rate* of
 * guesses once a source starts failing.
 *
 * Keyed by **client IP** (see [clientKey]), never by username. Keying on the username would let
 * anyone lock the two real accounts out at will (account-lockout DoS) — the usernames are known.
 * IP keying avoids that; the only cost is that two housemates behind one NAT briefly share a
 * bucket, which self-heals the moment either logs in successfully ([recordSuccess] clears it).
 *
 * Model: count *consecutive failures* per key. The first [maxFailures] are free (room for a
 * fat-fingered password); the next failure locks the key for [baseLockoutMillis], doubling with
 * each further failure up to [maxLockoutMillis] (exponential backoff). A failure older than
 * [failureWindowMillis] starts a fresh count, and any success wipes the key entirely — so a
 * legitimate user is never penalised for an eventually-correct login. While locked the request is
 * rejected (429) *before* the password is checked, so the throttle reveals nothing about which
 * accounts exist (it is purely IP-rate-based) and sheds load cheaply.
 *
 * State lives only in memory: a backend restart forgives everyone, which is fine for a private hub
 * and avoids a persistence dependency. All methods are synchronized on the instance; for a 2-user
 * hub the contention is irrelevant and the simple lock keeps the state transitions atomic.
 */
class LoginThrottler(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val baseLockoutMillis: Long = DEFAULT_BASE_LOCKOUT_MILLIS,
    private val maxLockoutMillis: Long = DEFAULT_MAX_LOCKOUT_MILLIS,
    private val failureWindowMillis: Long = DEFAULT_FAILURE_WINDOW_MILLIS,
    // Injectable so tests can advance time deterministically instead of sleeping.
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var failures: Int = 0, var lastFailureAt: Long = 0, var lockedUntil: Long = 0)

    private val states = HashMap<String, State>()

    /**
     * Seconds the [key] must wait before its next attempt, or 0 if it may try now. Call this
     * before verifying the password; a positive value means answer 429 and stop.
     */
    @Synchronized
    fun retryAfterSeconds(key: String): Long {
        val st = states[key] ?: return 0
        val remaining = st.lockedUntil - clock()
        // Round up so "0.4 s left" still reports ≥1 and the client doesn't retry a hair too early.
        return if (remaining > 0) (remaining + 999) / 1000 else 0
    }

    /** Record a failed attempt for [key], escalating the lockout once the free attempts run out. */
    @Synchronized
    fun recordFailure(key: String) {
        val now = clock()
        val st = states.getOrPut(key) { State() }
        if (now - st.lastFailureAt > failureWindowMillis) st.failures = 0
        st.failures += 1
        st.lastFailureAt = now
        if (st.failures >= maxFailures) st.lockedUntil = now + lockoutMillisFor(st.failures)
        if (states.size > PRUNE_THRESHOLD) {
            prune(now)
            // Hard cap: if pruning freed nothing (a sustained flood keeps every key fresh and
            // unexpired), drop the least-recently-active key so the map can never grow past the
            // threshold regardless of how many distinct sources attack.
            if (states.size > PRUNE_THRESHOLD) evictOldest()
        }
    }

    /** A successful login wipes the key's slate so the user starts fresh next time. */
    @Synchronized
    fun recordSuccess(key: String) {
        states.remove(key)
    }

    // base * 2^(failures past the threshold), capped at maxLockoutMillis. The shift is bounded so
    // it can never overflow Long before the cap clamps it.
    private fun lockoutMillisFor(failures: Int): Long {
        val over = failures - maxFailures
        if (over >= 20) return maxLockoutMillis
        return minOf(maxLockoutMillis, baseLockoutMillis shl over)
    }

    // Drop keys that are no longer locked and whose last failure aged out of the window, so a
    // spray of one-shot attempts from many IPs can't grow the map without bound.
    private fun prune(now: Long) {
        states.entries.removeIf { (_, st) ->
            st.lockedUntil <= now && now - st.lastFailureAt > failureWindowMillis
        }
    }

    private fun evictOldest() {
        states.entries.minByOrNull { it.value.lastFailureAt }?.let { states.remove(it.key) }
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_BASE_LOCKOUT_MILLIS = 60_000L        // 1 min after the free attempts
        const val DEFAULT_MAX_LOCKOUT_MILLIS = 900_000L        // capped at 15 min
        const val DEFAULT_FAILURE_WINDOW_MILLIS = 1_800_000L   // 30 min: forgive an abandoned run
        private const val PRUNE_THRESHOLD = 10_000
    }
}

/**
 * The throttle key for a login attempt: the real client IP, resolved spoof-resistantly.
 *
 * In production the chain is client → DSM reverse proxy → nginx (web container) → backend, so the
 * backend's direct peer is nginx and the client only shows up in `X-Forwarded-For`. A client can
 * prepend bogus entries to that header, so we never read the leftmost value. Instead each of our
 * [trustedProxyCount] trusted proxies appends exactly one entry (rightmost = nginx, next = DSM's
 * observed client), so the real client sits at index `size - trustedProxyCount`; anything further
 * left is attacker-supplied and ignored.
 *
 * **Deployment requirement:** this is only spoof-resistant if every trusted proxy actually appends
 * its observed peer. nginx does (`X-Forwarded-For $proxy_add_x_forwarded_for`, see nginx-spa.conf);
 * the DSM reverse proxy *must* be configured to record the real client IP too — otherwise the
 * backend sees only `[client-supplied], nginx-peer` and index `size - 2` is the attacker's value.
 * If DSM cannot add the hop, set `TRUSTED_PROXY_COUNT` to match the real number of appending
 * proxies. A *wrong* count fails safe toward over-throttling (it lands on an internal/proxy IP that
 * many clients share), never toward letting a spoofer mint fresh buckets.
 *
 * Falls back to the direct socket peer when there is no proxy ([trustedProxyCount] ≤ 0, e.g. local
 * `./gradlew run` and tests) or when the header is missing/shorter than expected (don't trust a
 * header that doesn't show the hops we require).
 */
fun clientKey(call: ApplicationCall, trustedProxyCount: Int): String {
    val direct = call.request.origin.remoteAddress
    if (trustedProxyCount <= 0) return direct
    val forwarded = call.request.headers["X-Forwarded-For"] ?: return direct
    val hops = forwarded.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return hops.getOrNull(hops.size - trustedProxyCount) ?: direct
}
