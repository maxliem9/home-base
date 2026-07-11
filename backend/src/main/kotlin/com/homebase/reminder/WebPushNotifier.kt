package com.homebase.reminder

import com.homebase.db.dbQuery
import com.homebase.db.PushSubscriptionsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.security.Security

/** A stored push subscription as needed to address one delivery. */
data class PushSubscriptionRow(val endpoint: String, val p256dh: String, val auth: String)

/**
 * The outcome of one push delivery — enough for the notifier to decide whether to prune the
 * subscription. [GONE] means the push service reported the subscription as expired/unsubscribed
 * (HTTP 404/410); the row should be removed. [SENT] is any 2xx; [FAILED] is a transient error
 * (network, 5xx, …) — keep the row and retry on the next reminder.
 */
enum class PushSendResult { SENT, GONE, FAILED }

/**
 * Thin seam over the actual web-push library so [WebPushNotifier] (the DB read + prune logic) is
 * unit-testable with a fake sender. The real implementation is [VapidWebPushSender].
 */
interface WebPushSender {
    fun send(sub: PushSubscriptionRow, payload: String): PushSendResult
}

/**
 * Real VAPID-signed sender backed by `nl.martijndwars:web-push` (#429 Phase 2b). Registers the
 * BouncyCastle JCE provider once (the library needs it for the ECDH/VAPID crypto). Constructed only
 * when all three VAPID values are set, so it never runs in a Telegram-only / local-dev deployment.
 *
 * Keys are base64url: [publicKey] the uncompressed P-256 point, [privateKey] the scalar; [subject]
 * a `mailto:` or `https:` contact URI per the VAPID spec.
 */
class VapidWebPushSender(publicKey: String, privateKey: String, subject: String) : WebPushSender {
    private val logger = LoggerFactory.getLogger(VapidWebPushSender::class.java)

    init {
        // MUST run before the pushService initializer below: PushService's constructor decodes the
        // VAPID public key via KeyFactory.getInstance(..., "BC"), so BouncyCastle has to be registered
        // first — otherwise the constructor throws NoSuchProviderException ("no such provider: BC") and
        // web push silently never enables. Kotlin runs init blocks and property initializers in
        // declaration order, so this block precedes `pushService`. Idempotent: addProvider is a no-op
        // if BouncyCastle is already registered.
        if (Security.getProvider(BouncyCastleProviderName) == null) {
            runCatching {
                val clazz = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                Security.addProvider(clazz.getDeclaredConstructor().newInstance() as java.security.Provider)
            }.onFailure { logger.warn("Could not register BouncyCastle provider for web push", it) }
        }
    }

    private val pushService = PushService(publicKey, privateKey, subject)

    override fun send(sub: PushSubscriptionRow, payload: String): PushSendResult {
        return try {
            val notification = Notification(sub.endpoint, sub.p256dh, sub.auth, payload)
            val response = pushService.send(notification)
            val status = response.statusLine.statusCode
            when {
                status in 200..299 -> PushSendResult.SENT
                // 404 Not Found / 410 Gone — the subscription is no longer valid; prune it.
                status == 404 || status == 410 -> PushSendResult.GONE
                else -> {
                    logger.warn("Web push delivery failed: HTTP {}", status)
                    PushSendResult.FAILED
                }
            }
        } catch (e: Exception) {
            logger.warn("Web push delivery error", e)
            PushSendResult.FAILED
        }
    }

    private companion object {
        const val BouncyCastleProviderName = "BC"
    }
}

/**
 * Delivers a reminder to every stored browser Web Push subscription (#429 Phase 2b). Subscriptions
 * the push service reports as gone (404/410) are pruned so dead endpoints don't accumulate. The
 * payload is a small JSON object the service worker reads to build the notification.
 *
 * Delivery is household-wide (every subscription), matching the digest's shared-chat model — the
 * stored username is informational for now (no per-user filtering).
 */
class WebPushNotifier(private val sender: WebPushSender) : ReminderNotifier {
    private val logger = LoggerFactory.getLogger(WebPushNotifier::class.java)

    override suspend fun notify(message: String) = withContext(Dispatchers.IO) {
        val subs = dbQuery {
            PushSubscriptionsTable.selectAll().map {
                PushSubscriptionRow(
                    endpoint = it[PushSubscriptionsTable.endpoint],
                    p256dh = it[PushSubscriptionsTable.p256dh],
                    auth = it[PushSubscriptionsTable.auth],
                )
            }
        }
        if (subs.isEmpty()) return@withContext

        val payload = buildPayload(message)
        val gone = mutableListOf<String>()
        for (sub in subs) {
            if (sender.send(sub, payload) == PushSendResult.GONE) gone += sub.endpoint
        }
        if (gone.isNotEmpty()) {
            dbQuery { PushSubscriptionsTable.deleteWhere { endpoint inList gone } }
            logger.info("Pruned {} gone web-push subscription(s)", gone.size)
        }
    }

    /**
     * JSON payload for the service worker's `push` handler. The message already reads as a full
     * sentence (e.g. "🔔 Erinnerung: Zahnarzt — fällig 14:30"); we split a leading "title:" if
     * present is overkill — keep it simple: a fixed title + the message as the body.
     */
    private fun buildPayload(message: String): String {
        val body = jsonEscape(message)
        return """{"title":"HomeBase","body":"$body"}"""
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
