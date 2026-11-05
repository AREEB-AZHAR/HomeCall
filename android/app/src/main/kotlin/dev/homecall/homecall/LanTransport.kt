package dev.homecall.homecall

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.*
import java.net.*
import java.security.*
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.*
import javax.net.ssl.*
import org.json.JSONObject

/**
 * A TLS handshake proves possession. Authorization uses the peer key digest at the application
 * layer.
 */
internal class LanTransport(
    private val store: KeyStore,
    private val connectivity: ConnectivityManager? = null,
    private val receive: (String, String, String) -> Unit,
) {
    constructor(
        context: Context,
        receive: (String, String, String) -> Unit,
    ) : this(
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) },
        context.getSystemService(ConnectivityManager::class.java),
        receive,
    )

    companion object {
        const val PORT = 43871
        private const val LIMIT = 98304
        private const val ALIAS = "homecall-identity-v1"

        private const val TLS_ALIAS = "homecall-tls-v2"

        private fun digest(key: PublicKey) =
            MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") {
                "%02x".format(it)
            }

        fun local(address: InetAddress) =
            address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress)

        fun interfaces() =
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter {
                    it.isUp &&
                        !it.isLoopback &&
                        !it.name.startsWith("rmnet") &&
                        !it.name.startsWith("tun")
                }
                .flatMap { it.interfaceAddresses }
                .filter { local(it.address) }

        private fun linkTo(remote: InetAddress, destination: InetAddress? = null): InetAddress? {
            if (!local(remote)) return null
            val target = remote.address
            return interfaces()
                .firstOrNull { link ->
                    if (destination != null && destination != link.address) return@firstOrNull false
                    val bytes = link.address.address
                    val prefix = link.networkPrefixLength.toInt()
                    prefix in 1..32 &&
                        (0 until prefix).all { bit ->
                            val mask = 128 shr (bit % 8)
                            ((bytes[bit / 8].toInt() xor target[bit / 8].toInt()) and mask) == 0
                        }
                }
                ?.address
        }
    }

    init {
        // Keep existing identity pins. TLS providers sign a precomputed digest and
        // need a distinct Keystore key authorized for NONEwithECDSA on Android.
        for (alias in listOf(ALIAS, TLS_ALIAS)) {
            if (store.containsAlias(alias)) continue
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                        )
                        .setAlgorithmParameterSpec(
                            java.security.spec.ECGenParameterSpec("secp256r1")
                        )
                        .setDigests(
                            *if (alias == TLS_ALIAS)
                                arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                            else arrayOf(KeyProperties.DIGEST_SHA256)
                        )
                        .build()
                )
                generateKeyPair()
            }
        }
    }

    // A phone can keep cellular as its default while Wi-Fi has no internet.
    // Select the Wi-Fi network owning the on-link route. Tethering hosts can
    // expose only a local interface, in which case use the interface fallback.
    private data class Route(val source: InetAddress, val network: Network? = null)

    @Suppress("DEPRECATION")
    private fun routeTo(remote: InetAddress, destination: InetAddress? = null): Route? {
        if (!local(remote)) return null
        connectivity?.let { cm ->
            for (network in cm.allNetworks) {
                if (
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
                )
                    continue
                val properties = cm.getLinkProperties(network) ?: continue
                if (
                    properties.routes.none {
                        !it.isDefaultRoute &&
                            it.gateway?.isAnyLocalAddress != false &&
                            it.matches(remote)
                    }
                )
                    continue
                val link =
                    properties.linkAddresses.firstOrNull {
                        local(it.address) && (destination == null || destination == it.address)
                    } ?: continue
                return Route(link.address, network)
            }
        }
        return linkTo(remote, destination)?.let { Route(it) }
    }

    private val identity = store.getCertificate(ALIAS).publicKey
    val id = digest(identity)
    private val bindingContext = "HomeCall TLS key binding v1".toByteArray()
    private val binding =
        JSONObject()
            .apply {
                val signature =
                    Signature.getInstance("SHA256withECDSA").apply {
                        initSign(store.getKey(ALIAS, null) as PrivateKey)
                        update(bindingContext)
                        update(store.getCertificate(TLS_ALIAS).publicKey.encoded)
                    }
                put("key", java.util.Base64.getEncoder().encodeToString(identity.encoded))
                put("signature", java.util.Base64.getEncoder().encodeToString(signature.sign()))
            }
            .toString()

    private fun authenticate(socket: SSLSocket): String {
        socket.startHandshake()
        write(socket.outputStream, binding)
        val proof = JSONObject(read(DataInputStream(socket.inputStream), 1024))
        val decoder = java.util.Base64.getDecoder()
        val key =
            KeyFactory.getInstance("EC")
                .generatePublic(
                    java.security.spec.X509EncodedKeySpec(decoder.decode(proof.getString("key")))
                )
        val valid =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(bindingContext)
                update(socket.session.peerCertificates.first().publicKey.encoded)
                verify(decoder.decode(proof.getString("signature")))
            }
        require(valid) { "Peer identity proof failed. Update both phones." }
        return digest(key)
    }

    private val keys =
        object : X509ExtendedKeyManager() {
            override fun chooseEngineClientAlias(
                types: Array<out String>?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ) = chooseClientAlias(types, issuers, null)

            override fun chooseEngineServerAlias(
                type: String?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ) = chooseServerAlias(type, issuers, null)

            override fun getPrivateKey(alias: String?) = store.getKey(TLS_ALIAS, null) as PrivateKey

            override fun getCertificateChain(alias: String?) =
                arrayOf(store.getCertificate(TLS_ALIAS) as X509Certificate)

            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) =
                arrayOf(TLS_ALIAS)

            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                if (type == "EC") arrayOf(TLS_ALIAS) else null

            override fun chooseClientAlias(
                types: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ) = if (types?.contains("EC") == true) TLS_ALIAS else null

            override fun chooseServerAlias(
                type: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ) = if (type == "EC") TLS_ALIAS else null
        }
    private val tls =
        SSLContext.getInstance("TLS").apply {
            init(
                arrayOf(keys),
                arrayOf(
                    object : X509TrustManager {
                        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                        override fun checkClientTrusted(
                            chain: Array<X509Certificate>,
                            auth: String,
                        ) {
                            require(chain.isNotEmpty())
                        }

                        override fun checkServerTrusted(
                            chain: Array<X509Certificate>,
                            auth: String,
                        ) {
                            require(chain.isNotEmpty())
                        }
                    }
                ),
                SecureRandom(),
            )
        }
    @Volatile private var server: SSLServerSocket? = null
    @Volatile var name = ""
    private val workers =
        ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

    private fun read(input: DataInputStream, limit: Int = LIMIT): String {
        val size = input.readInt()
        require(size in 1..limit)
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun write(output: OutputStream, body: String) {
        val bytes = body.toByteArray()
        require(bytes.size in 1..LIMIT)
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    private fun peerInfo(to: String, requestId: String) =
        JSONObject()
            .apply {
                put("v", 1)
                put("kind", "PEER_INFO")
                put("id", UUID.randomUUID().toString().replace("-", ""))
                put("from", id)
                put("to", to)
                put("session", "")
                put("at", System.currentTimeMillis())
                put("data", JSONObject().put("name", name).put("replyTo", requestId))
            }
            .toString()

    fun start() {
        if (server != null) return
        val listener =
            (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true
                needClientAuth = true
                enabledProtocols = arrayOf("TLSv1.2")
                bind(InetSocketAddress("0.0.0.0", PORT), 12)
            }
        server = listener
        Thread {
                while (!listener.isClosed) {
                    val socket =
                        try {
                            listener.accept() as SSLSocket
                        } catch (_: IOException) {
                            break
                        }
                    try {
                        workers.execute {
                            socket.use { s ->
                                runCatching {
                                    require(routeTo(s.inetAddress, s.localAddress) != null)
                                    s.soTimeout = 4000
                                    val peer = authenticate(s)
                                    val body = read(DataInputStream(s.inputStream))
                                    val message = JSONObject(body)
                                    require(message.getString("from") == peer)
                                    val requestId = message.getString("id")
                                    require(requestId.matches(Regex("[a-f0-9]{32}")))
                                    receive(peer, s.inetAddress.hostAddress!!, body)
                                    write(s.outputStream, peerInfo(peer, requestId))
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        socket.close()
                    }
                }
            }
            .start()
    }

    fun send(ip: String, expected: String?, body: String): Map<String, String> {
        require(ip.matches(Regex("[0-9.]{7,15}")))
        val address = InetAddress.getByName(ip)
        val route =
            requireNotNull(routeTo(address)) {
                "No directly connected local network for this address"
            }
        return Socket().use { tcp ->
            route.network?.bindSocket(tcp)
            tcp.bind(InetSocketAddress(route.source, 0))
            tcp.connect(InetSocketAddress(address, PORT), 3000)
            (tls.socketFactory.createSocket(tcp, ip, PORT, true) as SSLSocket).use { s ->
                s.enabledProtocols = arrayOf("TLSv1.2")
                s.soTimeout = 4000
                val peer = authenticate(s)
                require(peer != id && (expected == null || expected == peer)) {
                    "Device identity mismatch"
                }
                write(s.outputStream, body)
                val reply = JSONObject(read(DataInputStream(s.inputStream)))
                require(reply.getInt("v") == 1 && reply.getString("kind") == "PEER_INFO")
                require(reply.getString("from") == peer && reply.getString("to") == id)
                val data = reply.getJSONObject("data")
                require(data.getString("replyTo") == JSONObject(body).getString("id"))
                val label = data.getString("name")
                require(label.isNotBlank() && label.length <= 40)
                mapOf("id" to peer, "ip" to ip, "name" to label)
            }
        }
    }

    fun close() {
        server?.close()
        server = null
    }
}
