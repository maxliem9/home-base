package com.homebase.android.data.websocket

import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingListDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class ShoppingWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<ShoppingWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        data class ItemCreated(val item: ShoppingItemDto) : WsEvent()
        data class ItemUpdated(val item: ShoppingItemDto) : WsEvent()
        data class ItemDeleted(val item: ShoppingItemDto) : WsEvent()
        data class ListCreated(val list: ShoppingListDto) : WsEvent()
        data class ListUpdated(val list: ShoppingListDto) : WsEvent()
        data class ListDeleted(val list: ShoppingListDto) : WsEvent()
    }

    override val path = "/ws/shopping"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val type = moshi.adapter(TypeEnvelope::class.java).fromJson(text)?.type ?: return null
        return when (type) {
            "SHOPPING_CREATED" -> item(text)?.let { WsEvent.ItemCreated(it) }
            "SHOPPING_UPDATED" -> item(text)?.let { WsEvent.ItemUpdated(it) }
            "SHOPPING_DELETED" -> item(text)?.let { WsEvent.ItemDeleted(it) }
            "SHOPPING_LIST_CREATED" -> list(text)?.let { WsEvent.ListCreated(it) }
            "SHOPPING_LIST_UPDATED" -> list(text)?.let { WsEvent.ListUpdated(it) }
            "SHOPPING_LIST_DELETED" -> list(text)?.let { WsEvent.ListDeleted(it) }
            else -> null
        }
    }

    private fun item(text: String): ShoppingItemDto? =
        moshi.adapter(ItemEnvelope::class.java).fromJson(text)?.payload

    private fun list(text: String): ShoppingListDto? =
        moshi.adapter(ListEnvelope::class.java).fromJson(text)?.payload

    @JsonClass(generateAdapter = true)
    internal data class TypeEnvelope(val type: String)

    @JsonClass(generateAdapter = true)
    internal data class ItemEnvelope(val type: String, val payload: ShoppingItemDto? = null)

    @JsonClass(generateAdapter = true)
    internal data class ListEnvelope(val type: String, val payload: ShoppingListDto? = null)
}
