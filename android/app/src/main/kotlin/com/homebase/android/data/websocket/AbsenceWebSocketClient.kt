package com.homebase.android.data.websocket

import org.json.JSONObject

/**
 * Absence planner real-time channel. The backend sends a single message shape
 * `{ "type": "ABSENCE_CHANGED" }` on every mutation; clients react by refetching
 * the full snapshot (the planner state is small and always loaded whole). The
 * payload is trivial, so it is parsed with org.json rather than Moshi.
 */
class AbsenceWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<AbsenceWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        data object Changed : WsEvent()
    }

    override val path = "/ws/absence"

    override fun parse(text: String): WsEvent? =
        if (JSONObject(text).optString("type") == "ABSENCE_CHANGED") WsEvent.Changed else null
}
