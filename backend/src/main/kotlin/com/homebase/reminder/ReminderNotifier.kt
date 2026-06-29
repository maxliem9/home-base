package com.homebase.reminder

import com.homebase.digest.TelegramClient
import org.slf4j.LoggerFactory

/**
 * Delivery seam for a fired todo reminder (#429 Phase 2b). [ReminderService] decides *whether* to
 * fire (pure logic in [ReminderLogic]) and *what* the message text is, then hands the message to a
 * notifier — it does not care over which channel(s) it is delivered. This keeps the firing model
 * (fire-once, quiet hours, retire-stale) untouched while adding a second channel.
 *
 * Phase 2a delivered over Telegram only; Phase 2b adds Web Push. Both run side by side via
 * [CompositeReminderNotifier]; either can be dormant independently (no Telegram token → no
 * Telegram notifier; no VAPID keys → no web-push notifier).
 */
interface ReminderNotifier {
    /** Deliver one already-formatted reminder line. Best-effort: must not throw on a delivery error. */
    suspend fun notify(message: String)
}

/** Bridges the existing Telegram path onto the notifier seam (unchanged delivery behaviour). */
class TelegramReminderNotifier(private val client: TelegramClient) : ReminderNotifier {
    override suspend fun notify(message: String) = client.sendMessage(message)
}

/**
 * Fans a reminder out to every configured channel. Each notifier is best-effort and isolated:
 * a failure in one channel never blocks the others (an exception is logged, not propagated), so a
 * Telegram outage can't suppress a web push and vice versa.
 */
class CompositeReminderNotifier(private val notifiers: List<ReminderNotifier>) : ReminderNotifier {
    private val logger = LoggerFactory.getLogger(CompositeReminderNotifier::class.java)

    override suspend fun notify(message: String) {
        for (n in notifiers) {
            runCatching { n.notify(message) }
                .onFailure { logger.warn("Reminder notifier {} failed", n::class.simpleName, it) }
        }
    }
}
