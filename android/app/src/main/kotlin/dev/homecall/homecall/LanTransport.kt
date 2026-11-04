package dev.homecall.homecall

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.*
import java.net.*
import java.security.*
import java.security.cert.X509Certificate
import java.util.concurrent.*
import javax.net.ssl.*

/** A TLS handshake proves possession. Authorization uses the peer key digest at the application layer. */
internal class LanTransport(private val receive: (String, String, String) -> Unit) {
    companion object {
        const val PORT = 43871
        private const val LIMIT = 98304
        private const val ALIAS = "homecall-identity-v1"
        fun digest(cert: java.security.cert.Certificate) = MessageDigest.getInstance("SHA-256")
            .digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }
        fun local(address: InetAddress) = address is Inet4Address &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress)
    }
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build())
                generateKeyPair()
            }
        }
    }
    val id = digest(store.getCertificate(ALIAS))
    private val keys = object : X509ExtendedKeyManager() {
        override fun getPrivateKey(alias: String?) = store.getKey(ALIAS, null) as PrivateKey
        override fun getCertificateChain(alias: String?) = arrayOf(store.getCertificate(ALIAS) as X509Certificate)
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if(type == "EC") arrayOf(ALIAS) else null
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = if(types?.contains("EC")==true) ALIAS else null
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = if(type == "EC") ALIAS else null
    }
    private val tls = SSLContext.getInstance("TLS").apply {
        init(arrayOf(keys), arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { require(chain.isNotEmpty()) }
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) { require(chain.isNotEmpty()) }
        }), SecureRandom())
    }
    @Volatile private var server: SSLServerSocket? = null
    @Volatile var name = ""
    private val workers = ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    private fun read(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..LIMIT)
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
    private fun write(output: OutputStream, body: String) {
        val bytes = body.toByteArray()
        require(bytes.size in 1..LIMIT)
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    fun start() {
        if(server != null) return
        val listener = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
            bind(InetSocketAddress("0.0.0.0", PORT), 12)
        }
        server = listener
        Thread {
            while (!listener.isClosed) {
                val socket = try { listener.accept() as SSLSocket } catch (_: IOException) { break }
                try { workers.execute {
                    socket.use { s -> runCatching {
                        require(local(s.inetAddress))
                        s.soTimeout = 4000
                        s.startHandshake()
                        val peer = digest(s.session.peerCertificates.first())
                        val body = read(DataInputStream(s.inputStream))
                        receive(peer, s.inetAddress.hostAddress!!, body)
                        write(s.outputStream, name)
                    } }
                } } catch (_: RejectedExecutionException) { socket.close() }
            }
        }.start()
    }
    fun send(ip: String, expected: String?, body: String): Map<String,String> {
        require(ip.matches(Regex("[0-9.]{7,15}")))
        val address = InetAddress.getByName(ip)
        require(local(address))
        return (tls.socketFactory.createSocket() as SSLSocket).use { s ->
            s.enabledProtocols = arrayOf("TLSv1.2")
            s.soTimeout = 4000
            s.connect(InetSocketAddress(address, PORT), 3000)
            s.startHandshake()
            val peer = digest(s.session.peerCertificates.first())
            require(peer != id && (expected == null || expected == peer)) { "Device identity mismatch" }
            write(s.outputStream, body)
            mapOf("id" to peer, "ip" to ip, "name" to read(DataInputStream(s.inputStream)).take(40))
        }
    }
    fun close() { server?.close(); server = null; workers.shutdownNow() }
}

