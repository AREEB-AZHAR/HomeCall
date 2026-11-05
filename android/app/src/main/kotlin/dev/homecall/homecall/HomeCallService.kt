package dev.homecall.homecall

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder

class HomeCallService : Service() {
    private val manager
        get() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        manager.cancel(22)
        (application as HomeCallApp).stopListener()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun open(action: String, session: String = ""): PendingIntent =
        PendingIntent.getActivity(
            this,
            (action + session).hashCode(),
            Intent(this, MainActivity::class.java)
                .setAction(action)
                .putExtra("session", session)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        manager.createNotificationChannel(
            NotificationChannel(
                "available",
                "Local availability",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel("ring", "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .build(),
                    )
                }
        )
        val active = intent?.getBooleanExtra("active", false) == true
        val status =
            Notification.Builder(this, "available")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(if (active) "HomeCall voice call" else "HomeCall is available")
                .setContentText("Tap to open HomeCall")
                .setContentIntent(open("open"))
                .setOngoing(true)
        if (active && Build.VERSION.SDK_INT >= 31) {
            val sid = intent?.getStringExtra("session") ?: ""
            val end =
                PendingIntent.getBroadcast(
                    this,
                    sid.hashCode(),
                    Intent(this, CallAction::class.java)
                        .setAction("disconnect")
                        .putExtra("session", sid),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val person =
                Person.Builder().setName(intent?.getStringExtra("name") ?: "Household call").build()
            status.setStyle(Notification.CallStyle.forOngoingCall(person, end))
        }
        var ongoing = status.build()
        val session = intent?.getStringExtra("session") ?: ""
        if (intent?.action == "ring") {
            val name = intent.getStringExtra("name") ?: "Paired device"
            val answer = open("answer", session)
            val decline =
                PendingIntent.getBroadcast(
                    this,
                    session.hashCode(),
                    Intent(this, CallAction::class.java).putExtra("session", session),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val builder =
                Notification.Builder(this, "ring")
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setContentTitle(name)
                    .setContentText("Incoming HomeCall voice call")
                    .setContentIntent(open("open", session))
                    .setOngoing(true)
            if (Build.VERSION.SDK_INT >= 31)
                builder.setStyle(
                    Notification.CallStyle.forIncomingCall(
                        Person.Builder().setName(name).build(),
                        decline,
                        answer,
                    )
                )
            else
                builder
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this, android.R.drawable.sym_call_missed),
                            "Decline",
                            decline,
                        ).build()
                    )
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this, android.R.drawable.sym_call_incoming),
                            "Answer",
                            answer,
                        ).build()
                    )
            ongoing = builder.build()
        }
        // CallStyle must be the notification supplied to startForeground.
        // Use the same ID when returning to availability to remove ring actions.
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startForeground(
                    21,
                    ongoing,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        (if (active)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        else 0),
                )
            else startForeground(21, ongoing)
        } catch (e: SecurityException) {
            (application as HomeCallApp).event(
                "serviceError",
                mapOf(
                    "session" to (intent?.getStringExtra("session") ?: ""),
                    "text" to "Android restricted microphone access. Open HomeCall and retry.",
                ),
            )
            return START_NOT_STICKY
        }
        manager.cancel(22) // Remove a notification left by a previous app version.
        return START_NOT_STICKY
    }
}

class CallAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as HomeCallApp).event(
            if (intent.action == "disconnect") "disconnect" else "decline",
            mapOf("session" to (intent.getStringExtra("session") ?: "")),
        )
    }
}
