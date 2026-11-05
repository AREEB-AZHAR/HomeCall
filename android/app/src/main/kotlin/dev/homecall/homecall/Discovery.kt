package dev.homecall.homecall

import android.content.Context
import android.net.nsd.*
import android.os.Handler
import android.os.Looper

@Suppress("DEPRECATION")
internal class Discovery(
    context: Context,
    val id: String,
    val emit: (String, Map<String, String>) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val type = "_homecall._tcp"
    private val queue = LinkedHashMap<String, NsdServiceInfo>()
    private var busy = false
    private var closed = false
    private val registration =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                emit("notice", mapOf("text" to "Advertising unavailable. Use a local IP."))
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
    private val discovery =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}

            override fun onDiscoveryStopped(type: String) {}

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                emit("notice", mapOf("text" to "Discovery unavailable. Use a local IP."))
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) {}

            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                handler.post {
                    if (!closed && queue.size < 32 && info.serviceName != "HC-" + id.take(16)) {
                        queue[info.serviceName] = info
                        next()
                    }
                }
            }
        }

    private fun next() {
        if (closed || busy || queue.isEmpty()) return
        val info = queue.remove(queue.keys.first())!!
        busy = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    handler.post {
                        busy = false
                        next()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    handler.post {
                        val hosts =
                            if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses
                            else listOfNotNull(info.host)
                        if (!closed && info.port == LanTransport.PORT) {
                            hosts.filter(LanTransport::local).forEach { host ->
                                emit("found", mapOf("ip" to host.hostAddress!!))
                            }
                        }
                        busy = false
                        next()
                    }
                }
            },
        )
    }

    fun start() {
        nsd.registerService(
            NsdServiceInfo().apply {
                serviceName = "HC-" + id.take(16)
                serviceType = type
                port = LanTransport.PORT
                setAttribute("v", "1")
            },
            NsdManager.PROTOCOL_DNS_SD,
            registration,
        )
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun close() {
        closed = true
        runCatching { nsd.stopServiceDiscovery(discovery) }
        runCatching { nsd.unregisterService(registration) }
    }
}
