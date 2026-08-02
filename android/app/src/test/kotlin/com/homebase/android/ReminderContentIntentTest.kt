package com.homebase.android

import android.app.Application
import android.content.Intent
import com.homebase.android.notifications.ReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards the reminder notification's tap target: without a content intent the notification was inert
 * (a tap only dismissed it), so this pins down both that the notification *has* one and what
 * MainActivity has to receive to deep-link into the todo — action, id extra, singleTop routing flags.
 */
// A plain Application: the real HomeBaseApplication builds the AppContainer (WorkManager, network),
// which these pure intent/notification-shape checks have no use for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReminderContentIntentTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the notification has a tap target at all`() {
        // The regression itself: a builder without setContentIntent produces a notification whose tap
        // only dismisses it. Asserting the helper alone would not catch dropping it from the builder.
        val notification = ReminderWorker.buildNotification(context, "Müll rausbringen", "18:30", "todo-42")

        assertNotNull("reminder notification must be tappable", notification.contentIntent)
        val tapped = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ReminderWorker.ACTION_OPEN_TODO, tapped.action)
        assertEquals("todo-42", tapped.getStringExtra(ReminderWorker.EXTRA_TODO_ID))
    }

    @Test
    fun `open intent targets MainActivity and carries the todo id`() {
        val intent = ReminderWorker.openTodoIntent(context, "todo-42")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(ReminderWorker.ACTION_OPEN_TODO, intent.action)
        assertEquals("todo-42", intent.getStringExtra(ReminderWorker.EXTRA_TODO_ID))
    }

    @Test
    fun `open intent reuses the running activity instead of stacking a second one`() {
        val flags = ReminderWorker.openTodoIntent(context, "todo-42").flags

        // SINGLE_TOP + CLEAR_TOP → onNewIntent on the existing (launchMode=singleTop) MainActivity;
        // NEW_TASK covers the cold start out of a notification.
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `two todos never share a notification id or a PendingIntent slot`() {
        assertNotEquals(
            ReminderWorker.notificationId("todo-a"),
            ReminderWorker.notificationId("todo-b"),
        )
        assertTrue(ReminderWorker.notificationId("todo-a") >= 0)
        // PendingIntent identity ignores extras (Intent.filterEquals), so the per-todo data URI is
        // what keeps two pending reminders from collapsing onto one target if their ids ever collide.
        assertNotEquals(
            ReminderWorker.openTodoIntent(context, "todo-a").data,
            ReminderWorker.openTodoIntent(context, "todo-b").data,
        )
    }

    @Test
    fun `only a reminder intent with a non-empty id is a deep-link`() {
        assertEquals("todo-42", todoIdFrom(ReminderWorker.openTodoIntent(context, "todo-42")))
        assertNull("plain launcher start is no deep-link", todoIdFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(todoIdFrom(null))
        assertNull(
            "our action but no id → not a deep-link",
            todoIdFrom(Intent(ReminderWorker.ACTION_OPEN_TODO)),
        )
        assertNull(
            "empty id → not a deep-link",
            todoIdFrom(
                Intent(ReminderWorker.ACTION_OPEN_TODO)
                    .putExtra(ReminderWorker.EXTRA_TODO_ID, ""),
            ),
        )
        assertNull(
            "right extra under a foreign action must not deep-link",
            todoIdFrom(Intent(Intent.ACTION_VIEW).putExtra(ReminderWorker.EXTRA_TODO_ID, "todo-42")),
        )
    }
}
