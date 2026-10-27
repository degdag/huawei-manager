package com.nasser.huaweimanager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

object NetBinder {
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun bindToWifi(ctx: Context, timeoutMs: Long = 3000): Boolean {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val ready = CompletableDeferred<Boolean>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                ready.complete(true)
            }
            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
            }
        }
        callback = cb
        return try {
            cm.requestNetwork(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        } catch (e: Exception) { false }
    }
}
