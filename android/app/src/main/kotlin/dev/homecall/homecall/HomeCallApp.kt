package dev.homecall.homecall

import android.app.Application
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.*
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class HomeCallApp : Application() {
    lateinit var engine: FlutterEngine
    private lateinit var channel: MethodChannel
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val probes = Executors.newFixedThreadPool(2)
    private lateinit var transport: LanTransport
    private var discovery: Discovery? = null
    private var beacon: LanBeacon? = null
    private var lock: WifiManager.MulticastLock? = null
    internal val calls by lazy { CallIntegration(this) }

    internal fun ring(session: String, name: String) {
        startService(
            Intent(this, HomeCallService::class.java)
                .setAction("ring")
                .putExtra("session", session)
                .putExtra("name", name)
        )
    }

    var activity: MainActivity? = null

    fun event(type: String, args: Map<String, String>) {
        main.post { channel.invokeMethod(type, args) }
    }

    internal fun stopListener() {
        discovery?.close()
        discovery = null
        beacon?.close()
        beacon = null
        if (lock?.isHeld == true) lock?.release()
        lock = null
        if (::transport.isInitialized) transport.close()
        event("stopped", emptyMap())
    }

    override fun onCreate() {
        super.onCreate()
        engine = FlutterEngine(this)
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, "homecall/lan")
        channel.setMethodCallHandler { call, result ->
            fun arg(key: String) = call.argument<String>(key) ?: ""
            try {
                when (call.method) {
                    "identity" ->
                        io.execute {
                            runCatching {
                                    if (!::transport.isInitialized)
                                        transport =
                                            LanTransport(this) { id, ip, body ->
                                                event(
                                                    "message",
                                                    mapOf("id" to id, "ip" to ip, "body" to body),
                                                )
                                            }
                                    transport.id
                                }
                                .fold(
                                    { main.post { result.success(it) } },
                                    {
                                        main.post {
                                            result.error("identity", "Keystore unavailable", null)
                                        }
                                    },
                                )
                        }
                    "start" -> {
                        transport.name = arg("name")
                        io.execute {
                            try {
                                transport.start()
                                main.post {
                                    try {
                                        if (lock == null)
                                            lock =
                                                getSystemService(WifiManager::class.java)
                                                    .createMulticastLock("homecall")
                                                    .apply { acquire() }
                                        beacon?.close()
                                        beacon =
                                            LanBeacon { ip -> event("found", mapOf("ip" to ip)) }
                                                .also { it.start() }
                                        discovery?.close()
                                        discovery =
                                            Discovery(this, transport.id, ::event).also {
                                                it.start()
                                            }
                                        result.success(null)
                                    } catch (e: Exception) {
                                        event(
                                            "notice",
                                            mapOf(
                                                "text" to
                                                    "Android discovery unavailable. Try Pair by local IP."
                                            ),
                                        )
                                        result.success(null)
                                    }
                                }
                            } catch (e: Exception) {
                                main.post { result.error("listener", e.message, null) }
                            }
                        }
                    }
                    "telecom" ->
                        calls.request(
                            arg("session"),
                            arg("name"),
                            call.argument<Boolean>("incoming") == true,
                            result,
                        )
                    "endTelecom" -> {
                        activity?.clearCallWindow()
                        if (arg("session") == calls.session)
                            calls.end(
                                cause =
                                    when (arg("reason")) {
                                        "rejected" -> android.telecom.DisconnectCause.REJECTED
                                        "busy" -> android.telecom.DisconnectCause.BUSY
                                        "failed" -> android.telecom.DisconnectCause.ERROR
                                        else -> android.telecom.DisconnectCause.LOCAL
                                    }
                            )
                        result.success(null)
                    }
                    "speaker" -> {
                        calls.speaker(call.argument<Boolean>("value") == true)
                        result.success(null)
                    }
                    "service" -> {
                        if (arg("mode") == "active") {
                            require(
                                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                "Grant microphone permission in Android settings."
                            }
                            calls.active()
                        }
                        val intent =
                            Intent(this, HomeCallService::class.java)
                                .setAction(arg("mode"))
                                .putExtra("session", arg("session"))
                                .putExtra("name", arg("name"))
                                .putExtra("active", arg("mode") == "active")
                        startForegroundService(intent)
                        result.success(null)
                    }
                    "permissions" -> {
                        activity?.permissions()
                        result.success(null)
                    }
                    "addresses" ->
                        result.success(LanTransport.interfaces().map { it.address.hostAddress })
                    "send" ->
                        (if (call.argument<Boolean>("probe") == true) probes else io).execute {
                            runCatching {
                                    transport.send(
                                        arg("ip"),
                                        call.argument("expected"),
                                        arg("body"),
                                    )
                                }
                                .fold(
                                    { main.post { result.success(it) } },
                                    {
                                        main.post {
                                            result.error(
                                                "network",
                                                it.message ?: "Peer unreachable",
                                                null,
                                            )
                                        }
                                    },
                                )
                        }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("android", e.message, null)
            }
        }
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
    }
}
