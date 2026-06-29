package com.homebase.android.data.websocket

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Real-time channel for calendar events (#434/#435). The backend sends a bare
 * {"type":"EVENT_CHANGED"} on any event mutation (no payload), like the absence/meal-plan channels —
 * the client just reloads the visible range.
 */
class EventWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<EventWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        /** An event changed somewhere — reload the current range. */
        data object Changed : WsEvent()
    }

    override val path = "/ws/events"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val msg = moshi.adapter(WsPayload::class.java).fromJson(text) ?: return null
        return if (msg.type == "EVENT_CHANGED") WsEvent.Changed else null
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(val type: String)
}
