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
 * Coroutine-based scheduler that fires [runDigest] once per day at [digestTime].
 * Empty digests are skipped so the chat isn't spammed on quiet days.
 */
class DigestScheduler(
    // A provider, not a fixed value, so an edited digest time (#100) is picked up: the loop
    // re-reads it each iteration via [millisUntilNextRun], so a change applies from the next
    // scheduled run (the currently-pending run still fires at the previously-computed time).
    private val digestTime: () -> LocalTime,
    private val service: DigestService,
    private val client: TelegramClient,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val logger = LoggerFactory.getLogger(DigestScheduler::class.java)

    fun start() {
        scope.launch {
            while (isActive) {
                delay(millisUntilNextRun())
                runCatching { runDigest() }
                    .onFailure { logger.error("Digest run failed", it) }
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
        val content = service.buildDigest(today)
        if (content.isEmpty) {
            logger.info("Digest for {} is empty — skipping send", today)
            return
        }
        client.sendMessage(service.render(content))
        logger.info("Digest for {} sent", today)
    }
}
