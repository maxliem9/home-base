package com.homebase

import com.homebase.db.PushSubscriptionsTable
import com.homebase.reminder.CompositeReminderNotifier
import com.homebase.reminder.PushSendResult
import com.homebase.reminder.PushSubscriptionRow
import com.homebase.reminder.ReminderNotifier
import com.homebase.reminder.WebPushNotifier
import com.homebase.reminder.WebPushSender
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import com.homebase.reminder.VapidWebPushSender
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebPushNotifierTest {

    /** Records every (endpoint, payload) it was asked to send, and replies per endpoint. */
    private class FakeSender(private val results: Map<String, PushSendResult>) : WebPushSender {
        val sent = mutableListOf<Pair<String, String>>()
        override fun send(sub: PushSubscriptionRow, payload: String): PushSendResult {
            sent += sub.endpoint to payload
            return results[sub.endpoint] ?: PushSendResult.SENT
        }
    }

    private class CollectingNotifier : ReminderNotifier {
        val messages = mutableListOf<String>()
        override suspend fun notify(message: String) { messages.add(message) }
    }

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:push_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(PushSubscriptionsTable) }
    }

    private fun addSub(endpoint: String) = transaction {
        PushSubscriptionsTable.insert {
            it[id] = UUID.randomUUID()
            it[PushSubscriptionsTable.endpoint] = endpoint
            it[p256dh] = "key-$endpoint"
            it[auth] = "auth-$endpoint"
            it[username] = "alice"
            it[createdAt] = Instant.now()
        }
    }

    private fun endpoints(): Set<String> = transaction {
        PushSubscriptionsTable.selectAll().map { it[PushSubscriptionsTable.endpoint] }.toSet()
    }

    @Test
    fun `sends to every stored subscription`() = runBlocking {
        addSub("https://push/a")
        addSub("https://push/b")
        val sender = FakeSender(emptyMap())
        WebPushNotifier(sender).notify("🔔 Erinnerung: Test — fällig 14:30")

        assertEquals(setOf("https://push/a", "https://push/b"), sender.sent.map { it.first }.toSet())
        // payload is JSON the service worker reads; the message is the body
        assertTrue(sender.sent.all { it.second.contains("\"body\":\"🔔 Erinnerung: Test — fällig 14:30\"") })
        // nothing pruned
        assertEquals(setOf("https://push/a", "https://push/b"), endpoints())
    }

    @Test
    fun `prunes subscriptions reported gone (410) but keeps the rest`() = runBlocking {
        addSub("https://push/live")
        addSub("https://push/dead")
        addSub("https://push/flaky")
        val sender = FakeSender(
            mapOf(
                "https://push/dead" to PushSendResult.GONE,
                "https://push/flaky" to PushSendResult.FAILED, // transient → keep
            ),
        )
        WebPushNotifier(sender).notify("Hallo")

        // the gone one is removed; the live + transiently-failed ones survive
        assertEquals(setOf("https://push/live", "https://push/flaky"), endpoints())
    }

    @Test
    fun `no subscriptions is a no-op`() = runBlocking {
        val sender = FakeSender(emptyMap())
        WebPushNotifier(sender).notify("Hallo")
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `composite dispatches to every channel and isolates a failing one`() = runBlocking {
        val telegramLike = CollectingNotifier()
        val throwing = object : ReminderNotifier {
            override suspend fun notify(message: String) { throw RuntimeException("channel down") }
        }
        val webPushLike = CollectingNotifier()

        // throwing channel sits between the two healthy ones — must not block them
        CompositeReminderNotifier(listOf(telegramLike, throwing, webPushLike)).notify("Erinnerung")

        assertEquals(listOf("Erinnerung"), telegramLike.messages)
        assertEquals(listOf("Erinnerung"), webPushLike.messages)
    }

    @Test
    fun `a malformed VAPID key throws an Exception so boot wiring can catch and degrade`() {
        // PushService validates the keypair eagerly in VapidWebPushSender's constructor. The boot
        // wiring (configureTodoReminders) catches Exception — not Throwable — so a typo'd key disables
        // only web push instead of crash-looping the whole backend, while a real class-loading Error
        // (broken fat-jar packaging) still propagates. This pins that a bad key surfaces as Exception.
        assertFailsWith<Exception> {
            VapidWebPushSender("not-a-valid-key", "also-not-valid", "mailto:test@example.com")
        }
    }
}
