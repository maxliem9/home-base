package com.homebase.android.data.websocket

import com.homebase.android.data.model.ShoppingItemDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*

class ShoppingWebSocketClient(
    private val baseUrl: String,
    private val okHttp: OkHttp,
) {
    sealed class WsEvent {
        data class ItemCreated(val item: ShoppingItemDto) : WsEvent()
        data class ItemUpdated(val item: ShoppingItemDto) : WsEvent()
        data class ItemDeleted(val item: ShoppingItemDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().build()
    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/shopping"

        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val adapter = moshi.adapter(WsPayload::class.java)
                    val msg = adapter.fromJson(text) ?: return
                    val event = when (msg.type) {
                        "SHOPPING_CREATED" -> msg.payload?.let { WsEvent.ItemCreated(it) }
                        "SHOPPING_UPDATED" -> msg.payload?.let { WsEvent.ItemUpdated(it) }
                        "SHOPPING_DELETED" -> msg.payload?.let { WsEvent.ItemDeleted(it) }
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
    internal data class WsPayload(val type: String, val payload: ShoppingItemDto? = null)
}
