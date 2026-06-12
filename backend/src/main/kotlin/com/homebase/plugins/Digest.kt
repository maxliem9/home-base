package com.homebase.plugins

import com.homebase.db.AppSettingsTable
import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestService
import com.homebase.digest.HttpTelegramClient
import com.homebase.digest.MorningDigestService
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
 * Each send time is read fresh every scheduling cycle from `app_settings` (the value editable
 * in settings, #100), falling back to the configured default — so an in-app change applies
 * from the next scheduled run without a restart.
 */
fun Application.configureDigest() {
    val config = environment.config
    val botToken = config.propertyOrNull("telegram.botToken")?.getString()
    val chatId = config.propertyOrNull("telegram.chatId")?.getString()

    if (botToken.isNullOrBlank() || chatId.isNullOrBlank()) {
        log.info("Telegram digest disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set)")
        return
    }

    val client = HttpTelegramClient(botToken, chatId)

    val eveningDefault = parseDigestTime(config.propertyOrNull("telegram.digestTime")?.getString())
        ?: LocalTime.of(20, 0)
    val eveningTime = storedTimeProvider(AppSettingsTable.DIGEST_TIME, eveningDefault)
    DigestScheduler(
        digestTime = eveningTime,
        source = DigestService(),
        client = client,
        scope = this,
        label = "Evening digest",
    ).start()

    val morningDefault = parseDigestTime(config.propertyOrNull("telegram.morningDigestTime")?.getString())
        ?: LocalTime.of(7, 0)
    val morningTime = storedTimeProvider(AppSettingsTable.MORNING_DIGEST_TIME, morningDefault)
    DigestScheduler(
        digestTime = morningTime,
        source = MorningDigestService(),
        client = client,
        scope = this,
        label = "Morning digest",
    ).start()

    log.info("Telegram digests scheduled — morning {}, evening {} (overridable in settings)", morningTime(), eveningTime())
}

/**
 * A provider that re-reads an `app_settings` HH:mm time each call, falling back to [default]
 * when unset or malformed. The scheduler calls it every cycle, so an in-app edit applies from
 * the next run without a restart (#100).
 */
private fun storedTimeProvider(key: String, default: LocalTime): () -> LocalTime = {
    val stored = transaction {
        AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }
            .singleOrNull()?.get(AppSettingsTable.value)
    }
    parseDigestTime(stored) ?: default
}

/** Parses an "HH:mm" string into a [LocalTime], or null if it is blank or malformed. */
internal fun parseDigestTime(raw: String?): LocalTime? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
