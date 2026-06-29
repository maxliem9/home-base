package com.homebase.android.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.homebase.android.data.model.TodoDto
import com.homebase.android.ui.util.Format
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Bridges the todo list to WorkManager (#429 Phase 2c): given the current todos it schedules one
 * delayed one-shot [ReminderWorker] per timed, not-DONE todo at its (lead-adjusted) fire moment, and
 * cancels the work of any todo that has since been completed, retimed past its window, or deleted.
 *
 * Idempotent and cheap to call often — wire it to the same signals that refresh the list (WS reload,
 * an edit, app start). Each todo's work uses a stable unique name (its id), enqueued with
 * [ExistingWorkPolicy.REPLACE] so a reschedule overwrites the old delay rather than stacking.
 *
 * The "which todos → what fire instants" decision lives in the pure [ReminderPlan]; this class only
 * does the Android I/O so the timing rules stay unit-testable without WorkManager.
 */
class ReminderScheduler(
    context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now(zone) },
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    // The set of todo ids we last scheduled work for is persisted (not just in-memory): WorkManager
    // jobs survive process death, so the cancel set must too — otherwise a todo completed/deleted on
    // another device while this app was killed would leave an orphan job that fires a stale reminder.
    private val prefs = appContext.getSharedPreferences("reminder_scheduler", Context.MODE_PRIVATE)

    private fun loadScheduled(): Set<String> = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
    private fun saveScheduled(ids: Set<String>) = prefs.edit().putStringSet(KEY_SCHEDULED, ids).apply()

    /**
     * Reconcile the scheduled reminder work with [todos]: (re)enqueue the eligible ones and cancel
     * any previously-scheduled todo that is no longer eligible (completed / retimed out / deleted).
     */
    @Synchronized
    fun sync(todos: List<TodoDto>) {
        val plan = ReminderPlan.planReminders(
            todos = todos.map { it.toReminderInput() },
            now = clock(),
            zone = zone,
        )
        val planIds = plan.mapTo(mutableSetOf()) { it.todoId }

        // Cancel work for todos we'd scheduled before (durably tracked) but that fell out of the
        // plan — completed/retimed/deleted, even across an app kill since they were scheduled.
        for (gone in loadScheduled() - planIds) {
            workManager.cancelUniqueWork(workName(gone))
        }

        val now = clock().atZone(zone).toInstant().toEpochMilli()
        for (r in plan) {
            val delayMillis = (r.fireAtEpochMillis - now).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(Duration.ofMillis(delayMillis))
                .addTag(WORK_TAG)
                .setInputData(
                    Data.Builder()
                        .putString(ReminderWorker.KEY_TODO_ID, r.todoId)
                        .putString(ReminderWorker.KEY_TITLE, r.title)
                        .putString(ReminderWorker.KEY_DUE_LABEL, r.dueLabel)
                        .build(),
                )
                .build()
            // REPLACE: a reschedule (new due time / lead) overwrites the prior delay for this todo.
            workManager.enqueueUniqueWork(workName(r.todoId), ExistingWorkPolicy.REPLACE, request)
        }

        saveScheduled(planIds)
    }

    /** Cancel every scheduled reminder (e.g. on logout). */
    @Synchronized
    fun cancelAll() {
        workManager.cancelAllWorkByTag(WORK_TAG)
        prefs.edit().remove(KEY_SCHEDULED).apply()
    }

    private fun TodoDto.toReminderInput() = ReminderInput(
        id = id,
        title = title,
        status = status,
        dueDate = Format.parseLocalDate(dueDate),
        dueTime = Format.parseLocalTime(dueTime),
        reminderLeadMinutes = reminderLeadMinutes,
    )

    companion object {
        /** Shared tag on every reminder work request — used for blanket cancel on logout. */
        const val WORK_TAG = "todo_reminder"

        /** Persisted (process-death-durable) set of todo ids we currently hold reminder work for. */
        private const val KEY_SCHEDULED = "scheduled_ids"

        /** Stable unique-work name per todo so REPLACE targets exactly this todo's reminder. */
        fun workName(todoId: String): String = "todo_reminder_$todoId"
    }
}
