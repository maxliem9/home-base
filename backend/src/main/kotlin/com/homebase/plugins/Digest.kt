package com.homebase.plugins

import com.homebase.db.AppSettingsTable
import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestService
import com.homebase.digest.HttpTelegramClient
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalTime

/**
 * Starts the daily Telegram digest scheduler when a bot token and chat id are configured.
 * Without them the feature stays dormant (e.g. local dev), so the app runs without Telegram.
 *
 * The send time is read fresh each scheduling cycle from `app_settings.digest_time` (the
 * value editable in settings, #100), falling back to the configured default (telegram.digestTime,
 * "20:00") — so an in-app change applies from the next scheduled run without a restart.
 */
fun Application.configureDigest() {
    val config = environment.config
    val botToken = config.propertyOrNull("telegram.botToken")?.getString()
    val chatId = config.propertyOrNull("telegram.chatId")?.getString()

    if (botToken.isNullOrBlank() || chatId.isNullOrBlank()) {
        log.info("Telegram digest disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set)")
        return
    }

    val configuredDefault = parseDigestTime(config.propertyOrNull("telegram.digestTime")?.getString())
        ?: LocalTime.of(20, 0)

    val digestTimeProvider: () -> LocalTime = {
        val stored = transaction {
            AppSettingsTable.selectAll().where { AppSettingsTable.key eq AppSettingsTable.DIGEST_TIME }
                .singleOrNull()?.get(AppSettingsTable.value)
        }
        parseDigestTime(stored) ?: configuredDefault
    }

    DigestScheduler(
        digestTime = digestTimeProvider,
        service = DigestService(),
        client = HttpTelegramClient(botToken, chatId),
        scope = this,
    ).start()

    log.info("Telegram digest scheduled daily at {} (overridable in settings)", digestTimeProvider())
}

/** Parses an "HH:mm" string into a [LocalTime], or null if it is blank or malformed. */
internal fun parseDigestTime(raw: String?): LocalTime? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
