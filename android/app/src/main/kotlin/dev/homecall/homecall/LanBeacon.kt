package dev.homecall.homecall

import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** A broadcast supplies only an IP hint. The TLS probe must still verify the peer. */
internal class LanBeacon(private val found: (String) -> Unit) : Closeable {
    private val port = 43872
    private val marker = "HomeCall/discover/2".toByteArray(Charsets.US_ASCII)
    @Volatile private var closed = false
    @Volatile private var receiver: DatagramSocket? = null

    fun start() {
        thread(name = "homecall-beacon", isDaemon = true) {
            try {
                DatagramSocket(null).use { socket ->
                    receiver = socket
                    if (closed) return@thread
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress("0.0.0.0", port))
                    socket.soTimeout = 1000
                    val packet = DatagramPacket(ByteArray(marker.size + 1), marker.size + 1)
                    var nextSend = 0L
                    val seen = mutableSetOf<String>()
                    var own = emptySet<String?>()
                    while (!closed) {
                        if (SystemClock.elapsedRealtime() >= nextSend) {
                            val links = LanTransport.interfaces()
                            own = links.map { it.address.hostAddress }.toSet()
                            seen.clear()
                            for (link in links) {
                                val target = link.broadcast ?: continue
                                try {
                                    DatagramSocket(InetSocketAddress(link.address, 0)).use { sender
                                        ->
                                        sender.broadcast = true
                                        sender.send(
                                            DatagramPacket(marker, marker.size, target, port)
                                        )
                                    }
                                } catch (_: IOException) {
                                    /* Interface may have disconnected. */
                                }
                            }
                            nextSend = SystemClock.elapsedRealtime() + 10000
                        }
                        packet.length = packet.data.size
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val ip = packet.address.hostAddress ?: continue
                        if (
                            packet.length == marker.size &&
                                marker.indices.all { packet.data[it] == marker[it] } &&
                                LanTransport.local(packet.address) &&
                                ip !in own &&
                                seen.size < 64 &&
                                seen.add(ip)
                        ) {
                            found(ip)
                        }
                    }
                }
            } catch (_: IOException) {
                /* DNS-SD and direct IP remain available. */
            }
        }
    }

    override fun close() {
        closed = true
        receiver?.close()
    }
}
