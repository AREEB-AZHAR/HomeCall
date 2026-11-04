package dev.homecall.homecall

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.*
import io.flutter.plugin.common.MethodChannel

/** One platform Connection per live HomeCall session. All mutations run on the main looper. */
@Suppress("DEPRECATION")
internal class CallIntegration(private val app: HomeCallApp) {
    private val manager = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val handle =
        PhoneAccountHandle(ComponentName(app, HouseholdConnections::class.java), "household")
    private val main = Handler(Looper.getMainLooper())
    var session = ""
        private set

    private var name = ""
    private var pending: MethodChannel.Result? = null
    private var current: Connection? = null

    fun request(sid: String, label: String, incoming: Boolean, result: MethodChannel.Result) {
        require(session.isEmpty())
        manager.registerPhoneAccount(
            PhoneAccount.builder(handle, "HomeCall")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf("homecall"))
                .build()
        )
        session = sid
        name = label
        pending = result
        val address = Uri.fromParts("homecall", sid, null)
        try {
            if (incoming)
                manager.addNewIncomingCall(
                    handle,
                    Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
                        putString("homecall.sid", sid)
                    },
                )
            else
                manager.placeCall(
                    address,
                    Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    },
                )
            main.postDelayed(
                { if (session == sid && pending != null) end("Telecom did not admit this call") },
                6000,
            )
        } catch (e: Exception) {
            end(e.message ?: "Call unavailable")
        }
    }

    fun attach(sid: String, incoming: Boolean): Connection {
        if (sid != session || pending == null)
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        val connection =
            object : Connection() {
                override fun onAnswer() {
                    app.event("answer", mapOf("session" to sid))
                }

                override fun onReject() {
                    app.event("decline", mapOf("session" to sid))
                }

                override fun onDisconnect() {
                    app.event("disconnect", mapOf("session" to sid))
                }

                override fun onAbort() = onDisconnect()

                override fun onShowIncomingCallUi() {
                    if (session == sid) app.ring(sid, name)
                }

                override fun onCallAudioStateChanged(state: CallAudioState) {
                    if (session == sid)
                        app.event(
                            "route",
                            mapOf(
                                "session" to sid,
                                "speaker" to
                                    (state.route == CallAudioState.ROUTE_SPEAKER).toString(),
                                "muted" to state.isMuted.toString(),
                            ),
                        )
                }
            }
        connection.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connection.audioModeIsVoip = true
        connection.setAddress(
            Uri.fromParts("homecall", sid, null),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        connection.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        if (incoming) connection.setRinging() else connection.setDialing()
        current = connection
        pending?.success(null)
        pending = null
        return connection
    }

    fun active() {
        current?.setActive()
    }

    fun speaker(enabled: Boolean) {
        current?.setAudioRoute(
            if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        )
    }

    fun end(error: String? = null, cause: Int = DisconnectCause.LOCAL) {
        current?.setDisconnected(DisconnectCause(cause))
        current?.destroy()
        current = null
        pending?.error("telecom", error ?: "Call ended", null)
        pending = null
        session = ""
    }
}

class HouseholdConnections : ConnectionService() {
    private val calls
        get() = (application as HomeCallApp).calls

    private fun sid(request: ConnectionRequest) =
        request.address?.schemeSpecificPart ?: request.extras?.getString("homecall.sid") ?: ""

    override fun onCreateIncomingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) = calls.attach(sid(request), true)

    override fun onCreateOutgoingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) = calls.attach(sid(request), false)

    override fun onCreateIncomingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        if (sid(request) == calls.session) calls.end("Android declined the incoming call")
    }

    override fun onCreateOutgoingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        if (sid(request) == calls.session) calls.end("Another system call prevents calling")
    }
}
