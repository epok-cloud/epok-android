package dev.epok.rum

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

interface Connectivity {
    fun isOnline(): Boolean
    fun start(onChange: () -> Unit)
    fun stop()
}

class AndroidConnectivity(context: Context) : Connectivity {
    private val context = context.applicationContext
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun isOnline(): Boolean = try {
        val cm = manager ?: context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    override fun start(onChange: () -> Unit) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
        }
        cm.registerDefaultNetworkCallback(cb)
        manager = cm
        callback = cb
    }

    override fun stop() {
        try { callback?.let { manager?.unregisterNetworkCallback(it) } }
        finally { callback = null; manager = null }
    }
}

/** Deterministic sample/test switch. Does not change the emulator's actual connectivity. */
class ManualConnectivity(initiallyOnline: Boolean = true) : Connectivity {
    @Volatile private var online = initiallyOnline
    @Volatile private var listener: (() -> Unit)? = null
    override fun isOnline() = online
    override fun start(onChange: () -> Unit) { listener = onChange }
    override fun stop() { listener = null }
    fun setOnline(value: Boolean) { online = value; listener?.invoke() }
}
