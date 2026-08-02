package com.homebase.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homebase.android.MainActivity
import com.homebase.android.R

/**
 * Posts a single local reminder notification at its scheduled fire moment (#429 Phase 2c). Enqueued
 * (one delayed one-shot per timed todo) and cancelled by [ReminderScheduler]; carries the todo's
 * title + "HH:mm" due label in its input data so the worker needs no network or DB access.
 *
 * Device-local only: no server push, no FCM/Google dependency. If the todo is completed, retimed, or
 * deleted before the fire moment, [ReminderScheduler] cancels this work by its stable tag, so a
 * fired notification always reflects a still-pending todo as of the last reschedule.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val dueLabel = inputData.getString(KEY_DUE_LABEL) ?: return Result.success()
        val todoId = inputData.getString(KEY_TODO_ID) ?: return Result.success()

        ensureChannel(applicationContext)

        // Android 13+: posting requires the runtime POST_NOTIFICATIONS grant. If the user denied it,
        // degrade gracefully — drop the notification silently rather than crashing the worker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        // A stable per-todo notification id so a re-fire for the same todo replaces rather than stacks.
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationId(todoId), buildNotification(applicationContext, title, dueLabel, todoId))
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "todo_reminders"
        const val KEY_TODO_ID = "todoId"
        const val KEY_TITLE = "title"
        const val KEY_DUE_LABEL = "dueLabel"

        /** Intent action of the notification tap — distinguishes it from a plain launcher start. */
        const val ACTION_OPEN_TODO = "com.homebase.android.action.OPEN_TODO"

        /** Intent extra carrying the todo id the app should open on a notification tap. */
        const val EXTRA_TODO_ID = "com.homebase.android.extra.TODO_ID"

        /** Stable, non-negative notification id derived from the todo id. */
        fun notificationId(todoId: String): Int = todoId.hashCode() and 0x7FFFFFFF

        /**
         * The reminder notification itself. Extracted from [doWork] so a test can assert what a tap
         * actually does — the bug this fixes was a *missing* [NotificationCompat.Builder.setContentIntent],
         * which no test of the intent helper alone would catch.
         */
        internal fun buildNotification(context: Context, title: String, dueLabel: String, todoId: String) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.reminder_notification_text, title, dueLabel))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.reminder_notification_text, title, dueLabel)),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                // Tapping opens the app *on this todo*: without a content intent the notification was
                // inert — a tap only dismissed it.
                .setContentIntent(openTodoPendingIntent(context, todoId))
                .build()

        /**
         * The intent a notification tap delivers to [MainActivity]: open (or bring forward) the app
         * and deep-link to [todoId]. `SINGLE_TOP` + `CLEAR_TOP` together with the activity's
         * `launchMode="singleTop"` route it through `onNewIntent` on a running app instead of
         * stacking a second MainActivity; `NEW_TASK` covers the cold-start case.
         *
         * The `homebase://todo/<id>` data URI carries no routing meaning (the component is explicit) —
         * it makes two todos' intents distinct under `Intent.filterEquals`, which ignores extras, so
         * PendingIntent identity can never collapse two pending reminders onto one target.
         */
        fun openTodoIntent(context: Context, todoId: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_TODO
                data = Uri.parse("homebase://todo/${Uri.encode(todoId)}")
                putExtra(EXTRA_TODO_ID, todoId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        /**
         * [openTodoIntent] wrapped for the notification. The per-todo request code keeps two pending
         * reminders from sharing one PendingIntent, and `UPDATE_CURRENT` refreshes the extras of a
         * re-fired reminder for the same todo. Immutable, as Android 12+ requires.
         */
        private fun openTodoPendingIntent(context: Context, todoId: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId(todoId),
                openTodoIntent(context, todoId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Idempotently (re)create the dedicated reminder channel (no-op pre-Android 8). */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
            mgr.createNotificationChannel(channel)
        }
    }
}
