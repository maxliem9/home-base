package com.homebase.plugins

import com.homebase.recurrence.RecurringTodoScheduler
import com.homebase.recurrence.RecurringTodoService
import io.ktor.server.application.*
import java.time.LocalTime

/**
 * Starts the daily safety-net that rolls open, overdue recurring todos forward.
 * Always on — unlike the digest it needs no external credentials. The run time is configurable via
 * RECURRING_TIME (default 00:30, just after midnight so a freshly-due day is handled first thing).
 */
fun Application.configureRecurringTodos() {
    val config = environment.config
    val rawTime = config.propertyOrNull("recurring.time")?.getString() ?: "00:30"
    val runTime = runCatching { LocalTime.parse(rawTime) }.getOrElse {
        log.warn("Invalid RECURRING_TIME '{}', falling back to 00:30", rawTime)
        LocalTime.of(0, 30)
    }

    RecurringTodoScheduler(
        runTime = runTime,
        service = RecurringTodoService(),
        scope = this,
    ).start()

    log.info("Recurring-todo safety-net scheduled daily at {}", runTime)
}
