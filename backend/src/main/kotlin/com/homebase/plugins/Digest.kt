package com.homebase.plugins

import com.homebase.digest.DigestScheduler
import com.homebase.digest.DigestService
import com.homebase.digest.HttpTelegramClient
import io.ktor.server.application.*
import java.time.LocalTime

/**
 * Starts the daily Telegram digest scheduler when a bot token and chat id are configured.
 * Without them the feature stays dormant (e.g. local dev), so the app runs without Telegram.
 */
fun Application.configureDigest() {
    val config = environment.config
    val botToken = config.propertyOrNull("telegram.botToken")?.getString()
    val chatId = config.propertyOrNull("telegram.chatId")?.getString()

    if (botToken.isNullOrBlank() || chatId.isNullOrBlank()) {
        log.info("Telegram digest disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set)")
        return
    }

    val rawTime = config.propertyOrNull("telegram.digestTime")?.getString() ?: "20:00"
    val digestTime = runCatching { LocalTime.parse(rawTime) }.getOrElse {
        log.warn("Invalid DIGEST_TIME '{}', falling back to 20:00", rawTime)
        LocalTime.of(20, 0)
    }

    DigestScheduler(
        digestTime = digestTime,
        service = DigestService(),
        client = HttpTelegramClient(botToken, chatId),
        scope = this,
    ).start()

    log.info("Telegram digest scheduled daily at {}", digestTime)
}
