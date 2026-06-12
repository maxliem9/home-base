package com.homebase.recurrence

import com.homebase.routes.broadcastTodoUpdate
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
 * Coroutine-based scheduler that runs the recurring-todo safety-net once per day at [runTime],
 * mirroring the Telegram digest scheduler. Each open, overdue recurring todo whose
 * due date is roll-forward-eligible is advanced and broadcast as a TODO_UPDATED so connected
 * clients refresh it.
 */
class RecurringTodoScheduler(
    // A provider, not a fixed value, so an edited recurring time (#100) is picked up: the loop
    // re-reads it each iteration via [millisUntilNextRun], so a change applies from the next
    // scheduled run (the currently-pending run still fires at the previously-computed time).
    // Mirrors DigestScheduler.digestTime.
    private val runTime: () -> LocalTime,
    private val service: RecurringTodoService,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val logger = LoggerFactory.getLogger(RecurringTodoScheduler::class.java)

    fun start() {
        scope.launch {
            while (isActive) {
                delay(millisUntilNextRun())
                runCatching { runOnce() }
                    .onFailure { logger.error("Recurring-todo roll-forward failed", it) }
            }
        }
    }

    /** Milliseconds from now until the next occurrence of [runTime] in [zone]. */
    fun millisUntilNextRun(now: ZonedDateTime = ZonedDateTime.now(zone)): Long {
        var next = now.toLocalDate().atTime(runTime()).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    suspend fun runOnce(today: LocalDate = LocalDate.now(zone)) {
        val rolled = service.rollForwardOverdue(today)
        for (r in rolled) {
            // visibility is unchanged (we only moved the due date), so was/is shared are equal
            broadcastTodoUpdate(wasShared = r.shared, isShared = r.shared, todo = r.todo)
        }
        if (rolled.isNotEmpty()) {
            logger.info("Rolled {} overdue recurring todo(s) forward for {}", rolled.size, today)
        }
    }
}
