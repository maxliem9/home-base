package com.homebase.android.data.websocket

import com.homebase.android.data.model.RecipeDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*

class RecipeWebSocketClient(
    private val baseUrl: String,
    private val okHttp: OkHttp,
) {
    sealed class WsEvent {
        data class RecipeCreated(val recipe: RecipeDto) : WsEvent()
        data class RecipeUpdated(val recipe: RecipeDto) : WsEvent()
        data class RecipeDeleted(val recipe: RecipeDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/recipes"

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val adapter = moshi.adapter(WsPayload::class.java)
                    val msg = adapter.fromJson(text) ?: return
                    val event = when (msg.type) {
                        "RECIPE_CREATED" -> msg.payload?.let { WsEvent.RecipeCreated(it) }
                        "RECIPE_UPDATED" -> msg.payload?.let { WsEvent.RecipeUpdated(it) }
                        "RECIPE_DELETED" -> msg.payload?.let { WsEvent.RecipeDeleted(it) }
                        else -> null
                    }
                    event?.let { eventChannel.trySend(it) }
                }
            }
        }
        webSocket = okHttp.client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(1000, null)
        webSocket = null
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(val type: String, val payload: RecipeDto? = null)
}
