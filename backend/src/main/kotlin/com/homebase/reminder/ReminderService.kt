package com.homebase.reminder

import com.homebase.db.TodoAssigneesTable
import com.homebase.db.TodosTable
import com.homebase.notifications.privateTodoListIds
import com.homebase.notifications.todoIsShareable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One pass of the todo reminder scheduler (#429 Phase 2a): finds dated+timed, not-yet-reminded
 * todos whose reminder moment has arrived and delivers an immediate Telegram line, then stamps
 * `reminder_sent_at` so it never fires twice. Stale ones (well past their moment — first deploy /
 * long downtime) are retired silently.
 *
 * Privacy note: like the existing morning digest, this sends to the one shared household chat
 * (and, since #429 Phase 2b, to every registered browser) without per-*user* routing. Todos in a
 * PRIVATE list are omitted, though (via [privateTodoListIds]) — otherwise their title would leak to
 * the partner over the shared channel.
 *
 * Delivery channel(s) are abstracted behind [notifier] (#429 Phase 2b): the firing model is
 * channel-agnostic. In production it is a [CompositeReminderNotifier] over Telegram + Web Push,
 * either of which may be dormant.
 *
 * Settings (all re-read each tick, no restart): [enabled] (unset = on), and the optional
 * [quietStart]/[quietEnd] window during which the whole pass is skipped — reminders that came due
 * inside quiet hours are delivered at the first tick after the window ends (within [CATCHUP]).
 */
class ReminderService(
    private val notifier: ReminderNotifier,
    private val enabled: () -> Boolean = { true },
    private val quietStart: () -> LocalTime? = { null },
    private val quietEnd: () -> LocalTime? = { null },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val logger = LoggerFactory.getLogger(ReminderService::class.java)

    suspend fun runOnce(now: LocalDateTime = LocalDateTime.now(zone)) {
        if (!enabled()) return
        if (ReminderLogic.inQuietHours(now.toLocalTime(), quietStart(), quietEnd())) return

        // Collect + stamp inside the transaction; send the HTTP messages outside it (never hold a DB
        // transaction across network I/O). We stamp BEFORE sending: fire-once beats best-effort
        // delivery — a crash mid-send loses one reminder rather than risking a duplicate.
        val messages = transaction {
            val out = mutableListOf<String>()
            // Private-list todos never surface in the shared reminder (it reaches the one household
            // chat + all push devices) — that would leak their title to the partner.
            val privateLists = privateTodoListIds()
            TodosTable.selectAll().where {
                (TodosTable.status neq "DONE") and
                    TodosTable.dueDate.isNotNull() and
                    TodosTable.dueTime.isNotNull() and
                    TodosTable.reminderSentAt.isNull()
            }.forEach { row ->
                if (!row.todoIsShareable(privateLists)) return@forEach
                val candidate = ReminderCandidate(
                    title = row[TodosTable.title],
                    assignees = TodoAssigneesTable.selectAll()
                        .where { TodoAssigneesTable.todoId eq row[TodosTable.id] }
                        .orderBy(TodoAssigneesTable.username to SortOrder.ASC)
                        .map { it[TodoAssigneesTable.username] },
                    dueDate = row[TodosTable.dueDate]!!,
                    dueTime = row[TodosTable.dueTime]!!,
                    reminderLeadMinutes = row[TodosTable.reminderLeadMinutes],
                )
                when (ReminderLogic.decide(candidate, now)) {
                    ReminderLogic.Action.WAIT -> Unit
                    ReminderLogic.Action.FIRE -> {
                        stampSent(row[TodosTable.id])
                        out += ReminderLogic.message(candidate)
                    }
                    ReminderLogic.Action.RETIRE -> stampSent(row[TodosTable.id])
                }
            }
            out
        }

        messages.forEach { notifier.notify(it) }
        if (messages.isNotEmpty()) logger.info("Sent {} todo reminder(s)", messages.size)
    }

    private fun stampSent(id: java.util.UUID) {
        TodosTable.update({ TodosTable.id eq id }) { it[reminderSentAt] = Instant.now() }
    }
}
