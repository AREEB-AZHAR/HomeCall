package dev.homecall.homecall

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.util.UUID
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
        override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = chooseClientAlias(types,issuers,null)
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = chooseServerAlias(type,issuers,null)
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
    private fun peerInfo(to: String, requestId: String) = JSONObject().apply {
        put("v", 1)
        put("kind", "PEER_INFO")
        put("id", UUID.randomUUID().toString().replace("-", ""))
        put("from", id)
        put("to", to)
        put("session", "")
        put("at", System.currentTimeMillis())
        put("data", JSONObject().put("name", name).put("replyTo",requestId))
    }.toString()

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
                        val message = JSONObject(body)
                        require(message.getString("from") == peer)
                        val requestId = message.getString("id")
                        require(requestId.matches(Regex("[a-f0-9]{32}")))
                        receive(peer, s.inetAddress.hostAddress!!, body)
                        write(s.outputStream, peerInfo(peer,requestId))
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
            val reply = JSONObject(read(DataInputStream(s.inputStream)))
            require(reply.getInt("v")==1 && reply.getString("kind")=="PEER_INFO")
            require(reply.getString("from")==peer && reply.getString("to")==id)
            val data=reply.getJSONObject("data")
            require(data.getString("replyTo")==JSONObject(body).getString("id"))
            val label=data.getString("name")
            require(label.isNotBlank() && label.length<=40)
            mapOf("id" to peer, "ip" to ip, "name" to label)
        }
    }
    fun close() { server?.close(); server = null;  }
}

