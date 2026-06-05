package com.homebase.android.data.websocket

import com.homebase.android.data.model.RecipeDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class RecipeWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<RecipeWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        data class RecipeCreated(val recipe: RecipeDto) : WsEvent()
        data class RecipeUpdated(val recipe: RecipeDto) : WsEvent()
        data class RecipeDeleted(val recipe: RecipeDto) : WsEvent()
    }

    override val path = "/ws/recipes"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val msg = moshi.adapter(WsPayload::class.java).fromJson(text) ?: return null
        return when (msg.type) {
            "RECIPE_CREATED" -> msg.payload?.let { WsEvent.RecipeCreated(it) }
            "RECIPE_UPDATED" -> msg.payload?.let { WsEvent.RecipeUpdated(it) }
            "RECIPE_DELETED" -> msg.payload?.let { WsEvent.RecipeDeleted(it) }
            else -> null
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(val type: String, val payload: RecipeDto? = null)
}
