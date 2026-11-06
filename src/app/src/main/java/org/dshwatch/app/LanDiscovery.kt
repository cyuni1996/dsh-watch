package org.dshwatch.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.util.ArrayDeque

data class ConnectionCandidate(val id:String,val name:String,val url:String,val pin:String)

/** Foreground-only NSD. Serialized resolves also work on older Wear OS releases. */
class LanDiscovery(
    context: Context,
    private val found: (ConnectionCandidate) -> Unit,
    private val progress: (Int) -> Unit,
    private val finished: (String?) -> Unit,
) {
    companion object {
        const val SCAN_SECONDS = 15
    }
    private val manager=context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val ui=Handler(Looper.getMainLooper())
    private val lock=(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("dsh-watch-scan").apply { setReferenceCounted(false) }
    private val queue=ArrayDeque<NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var deadline = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (listener == null) return
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                finish()
                return
            }
            progress(((remaining + 999) / 1000).toInt())
            ui.postDelayed(this, minOf(remaining, 1000L))
        }
    }

    fun start() {
        stop()
        if (wifiSubnets().isEmpty()) {
            finished("手表未连接可用的 Wi-Fi。连接后请重新扫描。")
            return
        }
        val scan = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                ui.post {
                    val type = service.serviceType.trimEnd('.').removeSuffix(".local")
                    if (listener === this && type.equals("_dshwatch._tcp", ignoreCase = true)) {
                        queue.add(service)
                        resolve()
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(type: String) {
                ui.post { if (listener === this) finish("扫描已中断，请重新扫描。") }
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                ui.post { if (listener === this) finish("扫描启动失败（$code），请检查 Wi-Fi 后重试。") }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        listener = scan
        deadline = SystemClock.elapsedRealtime() + SCAN_SECONDS * 1000L
        runCatching {
            lock.acquire()
            manager.discoverServices("_dshwatch._tcp.", NsdManager.PROTOCOL_DNS_SD, scan)
            tick.run()
        }.onFailure { finish("无法启动局域网扫描，请检查 Wi-Fi 后重试。") }
    }

    private fun wifiSubnets(): List<Pair<String, Int>> = connectivity.allNetworks
        .filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
        .mapNotNull { link ->
            val address = link.address
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                address.hostAddress?.let { it to link.prefixLength }
            } else null
        }

    @Suppress("DEPRECATION") private fun resolve() {
        val scan = listener ?: return
        if (resolving || queue.isEmpty()) return
        resolving = true
        val service=queue.removeFirst()
        runCatching { manager.resolveService(service,object:NsdManager.ResolveListener {
            override fun onResolveFailed(info:NsdServiceInfo,code:Int) { ui.post { if(listener === scan) { resolving=false;resolve() } } }
            override fun onServiceResolved(info:NsdServiceInfo) { ui.post {
                if(listener !== scan)return@post
                resolving=false
                fun attr(key:String)=info.attributes[key]?.toString(Charsets.UTF_8) ?: ""
                val id=attr("id");val pin=attr("pin")
                val addresses=(attr("ipv4").split(',')+listOfNotNull(info.host?.hostAddress)).distinct()
                val address=ConnectionAddress.lanAddress(addresses,wifiSubnets()) ?: ""
                if(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && pin.matches(Regex("[a-f0-9]{64}")) && address.matches(Regex("[0-9.]+")) && info.port in 1..65535)
                    found(ConnectionCandidate(id,attr("name").take(40).ifBlank { info.serviceName.take(40) },"https://$address:${info.port}",pin))
                resolve()
            } }
        }) }.onFailure { resolving=false;resolve() }
    }

    private fun finish(error: String? = null) {
        stop()
        finished(error)
    }

    fun stop() {
        val scan = listener
        listener = null
        queue.clear()
        resolving = false
        ui.removeCallbacks(tick)
        if (scan != null) runCatching { manager.stopServiceDiscovery(scan) }
        if(lock.isHeld)lock.release()
    }
}
