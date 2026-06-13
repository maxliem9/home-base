package com.homebase.android.data.shopping

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits once whenever the device gains a usable default network — the Android analog of the web's
 * `window 'online'` event. The offline check-off queue subscribes to this to retry the moment
 * connectivity returns (one of three retry triggers, alongside the WS reconnect and the periodic
 * backstop; see [com.homebase.android.ui.shopping.ShoppingViewModel]).
 *
 * Uses [ConnectivityManager.registerDefaultNetworkCallback], so it tracks the network the app would
 * actually use. The callback is unregistered when the collector stops.
 *
 * `registerDefaultNetworkCallback` needs `ACCESS_NETWORK_STATE` (declared in the manifest). As
 * defense-in-depth — a stripped manifest or an OEM quirk must not hard-crash the Einkauf screen —
 * register/unregister are wrapped: on failure this flow simply never emits (the WS-reconnect and the
 * periodic backstop still drive the queue flush), rather than throwing out of the collector.
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Cold flow that emits [Unit] each time a default network becomes available. */
    val onAvailable: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        val registered = try {
            cm.registerDefaultNetworkCallback(callback)
            true
        } catch (e: SecurityException) {
            // Missing ACCESS_NETWORK_STATE (or revoked) — degrade gracefully, don't crash the flow.
            Log.w(TAG, "registerDefaultNetworkCallback denied; network-available retries disabled", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerDefaultNetworkCallback failed; network-available retries disabled", e)
            false
        }
        awaitClose {
            if (registered) {
                // unregister can throw if the callback was never (successfully) registered or the
                // service is gone — swallow so cleanup never crashes the collector's scope.
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "unregisterNetworkCallback failed", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ConnectivityObserver"
    }
}
