package com.homebase.digest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

interface TelegramClient {
    suspend fun sendMessage(text: String)
}

/**
 * Sends messages via the Telegram Bot API using the JDK HTTP client (no extra dependency).
 */
class HttpTelegramClient(
    private val botToken: String,
    private val chatId: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
) : TelegramClient {

    private val logger = LoggerFactory.getLogger(HttpTelegramClient::class.java)

    override suspend fun sendMessage(text: String) = withContext(Dispatchers.IO) {
        val body = "chat_id=" + encode(chatId) + "&text=" + encode(text)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot$botToken/sendMessage"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onSuccess { response ->
                if (response.statusCode() !in 200..299) {
                    logger.warn("Telegram sendMessage failed: {} {}", response.statusCode(), response.body())
                }
            }
            .onFailure { logger.warn("Telegram sendMessage error", it) }
        Unit
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
