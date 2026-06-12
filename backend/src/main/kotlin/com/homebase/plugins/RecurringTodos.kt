package com.homebase.plugins

import com.homebase.db.AppSettingsTable
import com.homebase.recurrence.RecurringTodoScheduler
import com.homebase.recurrence.RecurringTodoService
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalTime

/**
 * Starts the daily safety-net that rolls open, overdue recurring todos forward.
 * Always on — unlike the digest it needs no external credentials.
 *
 * The run time is read fresh each scheduling cycle from `app_settings.recurring_time` (the
 * value editable in settings, #100), falling back to the configured default (recurring.time,
 * RECURRING_TIME env, "00:30" just after midnight so a freshly-due day is handled first thing) —
 * so an in-app change applies from the next scheduled run without a restart. Mirrors the digest
 * scheduler (configureDigest).
 */
fun Application.configureRecurringTodos() {
    val config = environment.config
    val configuredDefault = parseDigestTime(config.propertyOrNull("recurring.time")?.getString())
        ?: LocalTime.of(0, 30)

    val runTimeProvider: () -> LocalTime = {
        val stored = transaction {
            AppSettingsTable.selectAll().where { AppSettingsTable.key eq AppSettingsTable.RECURRING_TIME }
                .singleOrNull()?.get(AppSettingsTable.value)
        }
        parseDigestTime(stored) ?: configuredDefault
    }

    RecurringTodoScheduler(
        runTime = runTimeProvider,
        service = RecurringTodoService(),
        scope = this,
    ).start()

    log.info("Recurring-todo safety-net scheduled daily at {} (overridable in settings)", runTimeProvider())
}
