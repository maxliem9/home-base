package com.homebase.ws

import com.homebase.model.SyncEnvelope
import com.homebase.plugins.appJson
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Registriert einen Echtzeit-Sync-Endpunkt unter `/ws/$channel` (Issue #552).
 *
 * Ersetzt die zuvor 8 wortwörtlich kopierten `webSocket("/ws/X"){ add; for(frame); remove }`-
 * Blöcke (Todo/Shopping/Notes/Time/Recipes/Absence/MealPlan/Events). Der Client sendet nicht,
 * er hört nur zu; die Schleife hält die Session offen und bricht beim Close-Frame ab (Ktor
 * schließt die Session ohnehin, sobald `incoming` endet). `add`/`remove` sind in `finally`
 * gekoppelt, damit die Session-Registry beim Verbindungsende immer aufräumt.
 */
fun Route.syncChannel(channel: String) = listenerWebSocket("/ws/$channel") {
    WsSessionManager.add(channel, this)
    try {
        for (frame in incoming) if (frame is Frame.Close) break
    } finally {
        WsSessionManager.remove(channel, this)
    }
}

/** Subprotokoll, in dem der Web-Client das JWT mitschickt (`new WebSocket(url, ["bearer", token])`). */
const val BEARER_WS_SUBPROTOCOL = "bearer"

/**
 * Registriert einen WebSocket-Endpunkt doppelt: einmal für Clients, die das JWT als Subprotokoll
 * schicken (Web), einmal ohne Subprotokoll (Android: `Authorization`-Header).
 *
 * Die `protocol`-Variante ist nötig, weil Ktor nur dann `Sec-WebSocket-Protocol: bearer` in die
 * 101-Antwort schreibt. Fehlt das Echo, obwohl der Client Subprotokolle angefragt hat, bricht der
 * Browser den Handshake ab (RFC 6455 §4.1) — der Client verbindet sich dann alle 3 s neu, pro
 * offenem Kanal, rund um die Uhr (Reconnect-Loop im nginx-Access-Log).
 * Ktors Selektor matcht nur, wenn der Request das Subprotokoll trägt; ohne fällt die Auflösung
 * auf die zweite Route.
 */
fun Route.listenerWebSocket(path: String, handler: suspend DefaultWebSocketServerSession.() -> Unit) {
    webSocket(path, protocol = BEARER_WS_SUBPROTOCOL, handler = handler)
    webSocket(path, handler = handler)
}

/**
 * Broadcastet einen type-only-Umschlag `{"type":"..."}` auf [channel] (MealPlan/Absence/Event).
 * Kein Payload — durch `encodeDefaults=false` entfällt der `payload`-Key vollständig.
 */
suspend fun WsSessionManager.broadcastSync(channel: String, type: String) =
    broadcast(channel, appJson.encodeToString(SyncEnvelope(type)))

/**
 * Broadcastet einen Umschlag `{"type":..., "payload":...}` auf [channel] (Issue #552, #134).
 *
 * [payload] wird über die zentrale [appJson] (encodeDefaults=false) zu einem [kotlinx.serialization.json.JsonElement]
 * kodiert und unverändert in den Umschlag gelegt — die verschachtelte Payload behält damit
 * byte-identisch dieselbe kompakte Form wie die früheren typisierten Envelope-DTOs. Ein
 * Kotlin-`null`-Payload wird als `null` an [SyncEnvelope] weitergereicht und vom encodeDefaults=false
 * weggelassen (statt als `"payload":null` serialisiert zu werden) — so bleibt die frühere
 * „payload optional"-Semantik erhalten.
 */
suspend fun <T> WsSessionManager.broadcastSync(
    channel: String,
    type: String,
    payload: T?,
    serializer: SerializationStrategy<T>,
) = broadcast(
    channel,
    appJson.encodeToString(
        SyncEnvelope(type, payload?.let { appJson.encodeToJsonElement(serializer, it) }),
    ),
)
