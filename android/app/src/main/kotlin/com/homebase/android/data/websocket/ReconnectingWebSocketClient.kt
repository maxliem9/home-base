package com.homebase.android.data.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Wraps the shared OkHttpClient so WS clients receive it without depending on the whole DI graph. */
class OkHttp(val client: OkHttpClient)

/**
 * Base for every real-time channel client. OkHttp WebSockets never reconnect on their own, and the
 * ViewModels that own them outlive navigation, so a socket dropped by a mobile-network change, Doze,
 * or a backend restart would otherwise stay silently dead until logout/login — the other user's
 * changes never arrive while the app still looks online, breaking the real-time-sync promise
 * (issue #54).
 *
 * This base owns the cure: it overrides [WebSocketListener.onFailure]/[WebSocketListener.onClosed]
 * and reconnects with exponential backoff (1s, 2s, 4s … capped at 30s), and [ensureConnected]
 * (called on app resume) immediately rebuilds a socket that died while the app was backgrounded.
 *
 * Subclasses implement only [path] (the channel suffix, e.g. `/ws/todos`) and [parse] (turn one text
 * frame into an event, or `null` to ignore it). Connection lifecycle, backoff, listener wiring and
 * the event [Channel] all live here; parsed events are exposed through [events].
 *
 * Thread-safety: connect/disconnect/ensureConnected run on the main thread while the OkHttp listener
 * callbacks and the backoff coroutine run on background threads, so every state transition is guarded
 * by [lock]. Stale callbacks from a socket we have already replaced are ignored via an identity check.
 */
abstract class ReconnectingWebSocketClient<E>(
    private val baseUrl: String,
    private val okHttp: OkHttp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Channel suffix appended to the ws base URL, e.g. `/ws/todos`. */
    protected abstract val path: String

    /** Parse one text frame into an event, or `null` to ignore it. Thrown exceptions are swallowed. */
    protected abstract fun parse(text: String): E?

    private val eventChannel = Channel<E>(Channel.BUFFERED)
    val events: Flow<E> = eventChannel.receiveAsFlow()

    /**
     * Invoked every time a socket finishes (re)connecting — a "server is reachable again" signal,
     * the mobile analog of the web's WS `onOpen`. Owners use it to flush work that should retry on
     * reconnect (e.g. the shopping offline check-off queue). Runs on an OkHttp background thread.
     */
    @Volatile
    var onConnected: (() -> Unit)? = null

    private val lock = Any()
    private var token: String? = null
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var closedByUs = false

    /** Open the channel for [token]. A later [disconnect] is required to stop reconnecting. */
    fun connect(token: String) = synchronized(lock) {
        this.token = token
        closedByUs = false
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        webSocket?.close(NORMAL_CLOSURE, null)
        openLocked()
    }

    /**
     * Re-open the socket if it is currently down — called when the app returns to the foreground,
     * where the OS may have silently killed the connection. Reconnects immediately instead of waiting
     * out any pending backoff, and no-ops when the socket is already up or was closed deliberately.
     */
    fun ensureConnected() = synchronized(lock) {
        if (closedByUs || token == null || webSocket != null) return@synchronized
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        openLocked()
    }

    /** Close the channel and stop reconnecting until the next [connect]. */
    fun disconnect() = synchronized(lock) {
        closedByUs = true
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        webSocket?.close(NORMAL_CLOSURE, null)
        webSocket = null
    }

    private fun openLocked() {
        val t = token ?: return
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + path
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $t")
            .build()
        webSocket = openSocket(request, listener)
    }

    private fun scheduleReconnectLocked() {
        webSocket = null
        if (closedByUs || reconnectJob?.isActive == true) return
        val delayMs = backoffMs(attempt)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            // Re-check under the lock: cancel() cannot stop an already-dispatched continuation, so if
            // disconnect() or an immediate ensureConnected() ran during the delay, the socket is gone
            // (closedByUs) or already re-opened (webSocket != null) — opening again would leak a socket.
            synchronized(lock) { if (!closedByUs && webSocket == null) openLocked() }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val isCurrent = synchronized(lock) {
                if (webSocket === this@ReconnectingWebSocketClient.webSocket) {
                    attempt = 0
                    true
                } else false
            }
            // Fire the reachable-again hook outside the lock — the callback may do real work
            // (e.g. a queue flush) and must not run while holding the connection lock.
            if (isCurrent) onConnected?.invoke()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { parse(text)?.let { eventChannel.trySend(it) } }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (webSocket === this@ReconnectingWebSocketClient.webSocket) scheduleReconnectLocked()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (webSocket === this@ReconnectingWebSocketClient.webSocket) scheduleReconnectLocked()
        }
    }

    /** Seam over [OkHttpClient.newWebSocket]; overridden in tests to drive the listener deterministically. */
    protected open fun openSocket(request: Request, listener: WebSocketListener): WebSocket =
        okHttp.client.newWebSocket(request, listener)

    private fun backoffMs(attempt: Int): Long =
        (BASE_BACKOFF_MS shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_SHIFT = 5 // 1s << 5 = 32s, capped to MAX_BACKOFF_MS
    }
}
