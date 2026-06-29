package com.homebase.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        val text = applicationContext.getString(R.string.reminder_notification_text, title, dueLabel)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // A stable per-todo notification id so a re-fire for the same todo replaces rather than stacks.
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationId(todoId), notification)
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "todo_reminders"
        const val KEY_TODO_ID = "todoId"
        const val KEY_TITLE = "title"
        const val KEY_DUE_LABEL = "dueLabel"

        /** Stable, non-negative notification id derived from the todo id. */
        fun notificationId(todoId: String): Int = todoId.hashCode() and 0x7FFFFFFF

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
