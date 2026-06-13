package com.homebase.digest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A daily Telegram message the [DigestScheduler] can deliver. Implementations build the
 * rendered text for a given day, or return null when there's nothing worth sending (so the
 * scheduler skips it and the chat isn't spammed on quiet days). Both the evening recap
 * ([DigestService]) and the morning briefing ([MorningDigestService]) are sources, so they
 * share one scheduler instead of duplicating the next-run timing logic.
 */
interface DigestSource {
    /** Rendered message for [today], or null if there's nothing to send. */
    fun buildMessage(today: LocalDate): String?
}

/**
 * Coroutine-based scheduler that fires [runDigest] once per day at [digestTime].
 * Empty messages are skipped so the chat isn't spammed on quiet days.
 */
class DigestScheduler(
    // A provider, not a fixed value, so an edited digest time (#100) is picked up: the loop
    // re-reads it each iteration via [millisUntilNextRun], so a change applies from the next
    // scheduled run (the currently-pending run still fires at the previously-computed time).
    private val digestTime: () -> LocalTime,
    private val source: DigestSource,
    private val client: TelegramClient,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
    // Human-readable name for logs, so the evening and morning runs are distinguishable.
    private val label: String = "Digest",
    // Per-digest on/off (#182), a provider like [digestTime] so an in-app toggle in app_settings is
    // honored from the next run without a restart. Checked at send time; a disabled digest skips
    // both building and sending. Defaults to always-on.
    private val enabled: () -> Boolean = { true },
) {
    private val logger = LoggerFactory.getLogger(DigestScheduler::class.java)

    fun start() {
        scope.launch {
            while (isActive) {
                delay(millisUntilNextRun())
                runCatching { runDigest() }
                    .onFailure { logger.error("{} run failed", label, it) }
            }
        }
    }

    /** Milliseconds from now until the next occurrence of [digestTime] in [zone]. */
    fun millisUntilNextRun(now: ZonedDateTime = ZonedDateTime.now(zone)): Long {
        var next = now.toLocalDate().atTime(digestTime()).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    suspend fun runDigest(today: LocalDate = LocalDate.now(zone)) {
        if (!enabled()) {
            logger.info("{} for {} disabled in settings — skipping send", label, today)
            return
        }
        val message = source.buildMessage(today)
        if (message == null) {
            logger.info("{} for {} is empty — skipping send", label, today)
            return
        }
        client.sendMessage(message)
        logger.info("{} for {} sent", label, today)
    }
}
