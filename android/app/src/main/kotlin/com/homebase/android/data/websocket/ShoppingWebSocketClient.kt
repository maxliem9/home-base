package com.homebase.android.data.websocket

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingListDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
        data class ListCreated(val list: ShoppingListDto) : WsEvent()
        data class ListUpdated(val list: ShoppingListDto) : WsEvent()
        data class ListDeleted(val list: ShoppingListDto) : WsEvent()
    }

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val eventChannel = Channel<WsEvent>(Channel.BUFFERED)
    private var webSocket: WebSocket? = null

    val events: Flow<WsEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String) {
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/shopping"

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val type = moshi.adapter(TypeEnvelope::class.java).fromJson(text)?.type ?: return
                    val event = when (type) {
                        "SHOPPING_CREATED" -> item(text)?.let { WsEvent.ItemCreated(it) }
                        "SHOPPING_UPDATED" -> item(text)?.let { WsEvent.ItemUpdated(it) }
                        "SHOPPING_DELETED" -> item(text)?.let { WsEvent.ItemDeleted(it) }
                        "SHOPPING_LIST_CREATED" -> list(text)?.let { WsEvent.ListCreated(it) }
                        "SHOPPING_LIST_UPDATED" -> list(text)?.let { WsEvent.ListUpdated(it) }
                        "SHOPPING_LIST_DELETED" -> list(text)?.let { WsEvent.ListDeleted(it) }
                        else -> null
                    }
                    event?.let { eventChannel.trySend(it) }
                }
            }
        }
        webSocket = okHttp.client.newWebSocket(request, listener)
    }

    private fun item(text: String): ShoppingItemDto? =
        moshi.adapter(ItemEnvelope::class.java).fromJson(text)?.payload

    private fun list(text: String): ShoppingListDto? =
        moshi.adapter(ListEnvelope::class.java).fromJson(text)?.payload

    fun disconnect() {
        webSocket?.close(1000, null)
        webSocket = null
    }

    @JsonClass(generateAdapter = true)
    internal data class TypeEnvelope(val type: String)

    @JsonClass(generateAdapter = true)
    internal data class ItemEnvelope(val type: String, val payload: ShoppingItemDto? = null)

    @JsonClass(generateAdapter = true)
    internal data class ListEnvelope(val type: String, val payload: ShoppingListDto? = null)
}
