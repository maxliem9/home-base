package com.homebase.android.data.shopping

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}
