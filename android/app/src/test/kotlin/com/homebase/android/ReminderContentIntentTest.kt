package com.homebase.android

import android.app.Application
import android.content.Intent
import com.homebase.android.notifications.ReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the reminder notification's tap target: without a content intent the notification was inert
 * (a tap only dismissed it), so this pins down what MainActivity has to receive to deep-link into the
 * todo — the action it filters on, the id extra, and the singleTop routing flags.
 */
// A plain Application: the real HomeBaseApplication builds the AppContainer (WorkManager, network),
// which this pure intent-shape check has no use for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReminderContentIntentTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

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
    fun `notification and pending-intent request ids are per todo`() {
        // Two reminders pending at once must not share a notification id or a PendingIntent slot,
        // or the second tap would open the first todo.
        assertNotEquals(
            ReminderWorker.notificationId("todo-a"),
            ReminderWorker.notificationId("todo-b"),
        )
        assertTrue(ReminderWorker.notificationId("todo-a") >= 0)
    }
}
