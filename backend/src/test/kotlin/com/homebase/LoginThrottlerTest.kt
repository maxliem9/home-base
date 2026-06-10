package com.homebase

import com.homebase.security.LoginThrottler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the login throttle logic (issue #8). A manually advanced clock lets us assert the
 * exact lockout windows and expiry without sleeping. HTTP wiring (429 shape, Retry-After header) is
 * covered separately in [AuthRouteTest].
 */
class LoginThrottlerTest {

    // A clock the test drives by hand; `now` is in epoch-millis-like units.
    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun throttler(
        clock: FakeClock,
        maxFailures: Int = 3,
        baseLockoutMillis: Long = 1_000L,
        maxLockoutMillis: Long = 8_000L,
        failureWindowMillis: Long = 60_000L,
    ) = LoginThrottler(maxFailures, baseLockoutMillis, maxLockoutMillis, failureWindowMillis, clock)

    @Test
    fun `a fresh key is never locked`() {
        val t = throttler(FakeClock())
        assertEquals(0, t.retryAfterSeconds("1.2.3.4"))
    }

    @Test
    fun `the first maxFailures attempts stay unlocked`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 3)
        t.recordFailure("ip")
        t.recordFailure("ip")
        // Two failures, still one free attempt left.
        assertEquals(0, t.retryAfterSeconds("ip"))
    }

    @Test
    fun `the maxFailures-th failure locks the key for the base duration`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 3, baseLockoutMillis = 1_000L)
        repeat(3) { t.recordFailure("ip") }
        assertEquals(1, t.retryAfterSeconds("ip"))
    }

    @Test
    fun `lockout backs off exponentially and is capped`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 1, baseLockoutMillis = 1_000L, maxLockoutMillis = 4_000L)
        // failures=1 -> base * 2^0 = 1s
        t.recordFailure("ip")
        assertEquals(1, t.retryAfterSeconds("ip"))
        // failures=2 -> base * 2^1 = 2s (advance past the current lock so the next attempt counts)
        clock.now += 1_000L
        t.recordFailure("ip")
        assertEquals(2, t.retryAfterSeconds("ip"))
        // failures=3 -> base * 2^2 = 4s
        clock.now += 2_000L
        t.recordFailure("ip")
        assertEquals(4, t.retryAfterSeconds("ip"))
        // failures=4 -> base * 2^3 = 8s, clamped to the 4s cap
        clock.now += 4_000L
        t.recordFailure("ip")
        assertEquals(4, t.retryAfterSeconds("ip"))
    }

    @Test
    fun `the key unlocks once the lockout elapses`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 3, baseLockoutMillis = 2_000L)
        repeat(3) { t.recordFailure("ip") }
        assertEquals(2, t.retryAfterSeconds("ip"))
        clock.now += 2_000L
        assertEquals(0, t.retryAfterSeconds("ip"))
    }

    @Test
    fun `retryAfter rounds up partial seconds`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 1, baseLockoutMillis = 1_500L)
        t.recordFailure("ip")
        // 1.5 s remaining must report 2, never 1 (so a client never retries a hair too early).
        assertEquals(2, t.retryAfterSeconds("ip"))
    }

    @Test
    fun `a success wipes the slate`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 3)
        repeat(2) { t.recordFailure("ip") }
        t.recordSuccess("ip")
        // Counter reset: it now takes a full maxFailures again to lock.
        t.recordFailure("ip")
        t.recordFailure("ip")
        assertEquals(0, t.retryAfterSeconds("ip"))
        t.recordFailure("ip")
        assertTrue(t.retryAfterSeconds("ip") > 0)
    }

    @Test
    fun `failures older than the window start a fresh count`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 3, failureWindowMillis = 10_000L)
        t.recordFailure("ip")
        t.recordFailure("ip")
        // Long gap: the stale failures are forgotten, so three more are needed to lock.
        clock.now += 10_001L
        t.recordFailure("ip")
        t.recordFailure("ip")
        assertEquals(0, t.retryAfterSeconds("ip"))
        t.recordFailure("ip")
        assertTrue(t.retryAfterSeconds("ip") > 0)
    }

    @Test
    fun `different keys are throttled independently`() {
        val clock = FakeClock()
        val t = throttler(clock, maxFailures = 3)
        repeat(3) { t.recordFailure("attacker") }
        assertTrue(t.retryAfterSeconds("attacker") > 0)
        assertEquals(0, t.retryAfterSeconds("victim"))
    }
}
