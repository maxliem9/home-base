package com.homebase.plugins

import com.homebase.db.AppSettingsTable
import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestSection
import com.homebase.digest.DigestService
import com.homebase.digest.HttpTelegramClient
import com.homebase.digest.MorningDigestService
import com.homebase.reminder.CompositeReminderNotifier
import com.homebase.reminder.ReminderNotifier
import com.homebase.reminder.ReminderScheduler
import com.homebase.reminder.ReminderService
import com.homebase.reminder.TelegramReminderNotifier
import com.homebase.reminder.VapidWebPushSender
import com.homebase.reminder.WebPushNotifier
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalTime

/**
 * Starts the daily Telegram digest schedulers when a bot token and chat id are configured.
 * Without them the feature stays dormant (e.g. local dev), so the app runs without Telegram.
 *
 * Two daily messages, both over the same bot/chat:
 *  - the evening recap ([DigestService]: done today / new inbox / due tomorrow), default 20:00;
 *  - the morning briefing ([MorningDigestService]: due today / overdue / inbox / absences /
 *    kita closures), default 07:00.
 *
 * Each send time, the per-digest on/off flag, and the per-digest content-section selection are
 * read fresh every scheduling cycle from `app_settings` (all editable in settings, #100/#182),
 * falling back to defaults — so an in-app change applies from the next scheduled run without a
 * restart. A disabled digest skips entirely; deselected sections aren't rendered.
 */
fun Application.configureDigest() {
    val config = environment.config
    val botToken = config.propertyOrNull("telegram.botToken")?.getString()
    val chatId = config.propertyOrNull("telegram.chatId")?.getString()
    val telegramConfigured = !botToken.isNullOrBlank() && !chatId.isNullOrBlank()
    val client = if (telegramConfigured) HttpTelegramClient(botToken!!, chatId!!) else null

    // Todo reminders (#429): delivered over Telegram (Phase 2a) AND/OR browser Web Push
    // (Phase 2b). The reminder scheduler runs when EITHER channel is configured — so a
    // VAPID-only deployment still gets reminders, and a Telegram-only one is unchanged.
    configureTodoReminders(telegramClient = client)

    if (client == null) {
        log.info("Telegram digest disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set)")
        return
    }

    val eveningDefault = parseDigestTime(config.propertyOrNull("telegram.digestTime")?.getString())
        ?: LocalTime.of(20, 0)
    val eveningTime = storedTimeProvider(AppSettingsTable.DIGEST_TIME, eveningDefault)
    DigestScheduler(
        digestTime = eveningTime,
        source = DigestService(sections = storedSectionsProvider(AppSettingsTable.DIGEST_EVENING_SECTIONS, DigestSection.evening)),
        client = client,
        scope = this,
        label = "Evening digest",
        enabled = storedEnabledProvider(AppSettingsTable.DIGEST_EVENING_ENABLED),
    ).start()

    val morningDefault = parseDigestTime(config.propertyOrNull("telegram.morningDigestTime")?.getString())
        ?: LocalTime.of(7, 0)
    val morningTime = storedTimeProvider(AppSettingsTable.MORNING_DIGEST_TIME, morningDefault)
    DigestScheduler(
        digestTime = morningTime,
        source = MorningDigestService(sections = storedSectionsProvider(AppSettingsTable.DIGEST_MORNING_SECTIONS, DigestSection.morning)),
        client = client,
        scope = this,
        label = "Morning digest",
        enabled = storedEnabledProvider(AppSettingsTable.DIGEST_MORNING_ENABLED),
    ).start()

    log.info("Telegram digests scheduled — morning {}, evening {} (overridable in settings)", morningTime(), eveningTime())
}

/**
 * Wires the immediate per-todo reminder scheduler (#429): a tight tick that fires near a todo's due
 * time. Built over a [CompositeReminderNotifier] of the channels that are configured —
 * Telegram (Phase 2a, [telegramClient] non-null) and/or browser Web Push (Phase 2b, VAPID keys
 * set). If neither channel is configured the scheduler is not started (nothing to deliver to);
 * a Telegram-only deployment behaves exactly as before. Enabled by default (unset) with an
 * optional in-app quiet-hours window.
 */
private fun Application.configureTodoReminders(telegramClient: com.homebase.digest.TelegramClient?) {
    val config = environment.config
    val notifiers = mutableListOf<ReminderNotifier>()

    if (telegramClient != null) notifiers += TelegramReminderNotifier(telegramClient)

    val vapidPublic = config.propertyOrNull("webpush.publicKey")?.getString()
    val vapidPrivate = config.propertyOrNull("webpush.privateKey")?.getString()
    val vapidSubject = config.propertyOrNull("webpush.subject")?.getString()
    if (!vapidPublic.isNullOrBlank() && !vapidPrivate.isNullOrBlank() && !vapidSubject.isNullOrBlank()) {
        // PushService validates the VAPID keypair eagerly in its constructor — a malformed key throws
        // IllegalArgumentException. Catch *Exception* (not Throwable) so a typo'd key only disables web
        // push (degrade to Telegram-only) instead of crash-looping the whole backend at boot. A genuine
        // packaging/class-loading failure (NoClassDefFoundError etc.) is an Error and still propagates,
        // so the fat-jar BC smoke test stays meaningful.
        try {
            notifiers += WebPushNotifier(VapidWebPushSender(vapidPublic, vapidPrivate, vapidSubject))
            // NOTE: this exact line is grepped verbatim by the fat-jar smoke test in ci.yml (#474) to
            // prove BouncyCastle/PushService loaded from the shaded jar — don't reword without updating it.
            log.info("Web Push enabled for todo reminders (VAPID configured)")
        } catch (e: Exception) {
            log.error("Web Push disabled: invalid VAPID configuration (keys present but rejected)", e)
        }
    } else {
        log.info("Web Push disabled for todo reminders (VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY / VAPID_SUBJECT not set)")
    }

    if (notifiers.isEmpty()) {
        log.info("Todo reminders disabled (no Telegram and no Web Push configured)")
        return
    }

    ReminderScheduler(
        service = ReminderService(
            notifier = CompositeReminderNotifier(notifiers),
            enabled = storedEnabledProvider(AppSettingsTable.REMINDERS_ENABLED),
            quietStart = { parseDigestTime(readSetting(AppSettingsTable.REMINDER_QUIET_START)) },
            quietEnd = { parseDigestTime(readSetting(AppSettingsTable.REMINDER_QUIET_END)) },
        ),
        scope = this,
    ).start()
    log.info("Todo reminders scheduled (overridable in settings)")
}

/**
 * A provider that re-reads an `app_settings` HH:mm time each call, falling back to [default]
 * when unset or malformed. The scheduler calls it every cycle, so an in-app edit applies from
 * the next run without a restart (#100).
 */
private fun storedTimeProvider(key: String, default: LocalTime): () -> LocalTime = {
    parseDigestTime(readSetting(key)) ?: default
}

/**
 * Per-digest on/off provider (#182): re-reads the `app_settings` flag each call so an in-app
 * toggle applies from the next run. Unset (fresh DB) means on, so behavior is unchanged until
 * someone disables a digest. Only "false" disables.
 */
private fun storedEnabledProvider(key: String): () -> Boolean = {
    readSetting(key)?.equals("false", ignoreCase = true) != true
}

/**
 * Per-digest section-selection provider (#182): re-reads the persisted CSV of section ids each
 * call (intersected with [allowed]); unset selects all of [allowed], so a fresh DB renders the
 * full digest. The scheduler picks up an in-app change from the next run.
 */
private fun storedSectionsProvider(key: String, allowed: List<DigestSection>): () -> Set<DigestSection> = {
    DigestSection.parseSelection(readSetting(key), allowed)
}

/** Reads one `app_settings` value by key, or null if unset. */
private fun readSetting(key: String): String? = transaction {
    AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }
        .singleOrNull()?.get(AppSettingsTable.value)
}

/** Parses an "HH:mm" string into a [LocalTime], or null if it is blank or malformed. */
internal fun parseDigestTime(raw: String?): LocalTime? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
