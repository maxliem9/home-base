package com.homebase.android.data.websocket

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Real-time channel for the Wochenplan (#250). The backend sends a bare {"type":"MEAL_PLAN_CHANGED"}
 * on any mutation (no payload), like the absence calendar — the client just reloads the visible week.
 */
class MealPlanWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<MealPlanWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        /** The meal plan changed somewhere — reload the current range. */
        data object Changed : WsEvent()
    }

    override val path = "/ws/meal-plan"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val msg = moshi.adapter(WsPayload::class.java).fromJson(text) ?: return null
        return if (msg.type == "MEAL_PLAN_CHANGED") WsEvent.Changed else null
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(val type: String)
}
