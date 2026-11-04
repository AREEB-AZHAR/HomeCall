package dev.homecall.homecall

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.media.AudioAttributes
import android.media.RingtoneManager

class HomeCallService : Service() {
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null
    private fun open(action: String, session: String = ""): PendingIntent =
        PendingIntent.getActivity(this, (action+session).hashCode(), Intent(this, MainActivity::class.java)
            .setAction(action).putExtra("session",session).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        manager.createNotificationChannel(NotificationChannel("available","Local availability",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("ring","Incoming calls",NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        })
        val active = intent?.getBooleanExtra("active",false) == true
        val ongoing = Notification.Builder(this,"available").setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(if(active) "HomeCall voice call" else "HomeCall is available")
            .setContentText("Tap to open HomeCall").setContentIntent(open("open")).setOngoing(true).build()
        if(Build.VERSION.SDK_INT >= 30) startForeground(21,ongoing,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if(active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
        else startForeground(21,ongoing)
        val session = intent?.getStringExtra("session") ?: ""
        if(intent?.action == "ring") {
            val name = intent.getStringExtra("name") ?: "Paired device"
            val answer = open("answer",session)
            val decline = PendingIntent.getBroadcast(this,session.hashCode(),Intent(this,CallAction::class.java).putExtra("session",session),PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder(this,"ring").setSmallIcon(android.R.drawable.sym_call_incoming)
                .setCategory(Notification.CATEGORY_CALL).setContentTitle(name).setContentText("Incoming HomeCall voice call")
                .setContentIntent(open("open",session)).setTimeoutAfter(30000)
            if(Build.VERSION.SDK_INT >= 31) builder.setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(name).build(),decline,answer))
            else builder.addAction(android.R.drawable.sym_call_missed,"Decline",decline).addAction(android.R.drawable.sym_call_incoming,"Answer",answer)
            manager.notify(22,builder.build())
        } else manager.cancel(22)
        return START_NOT_STICKY
    }
}
class CallAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as HomeCallApp).event("decline", mapOf("session" to (intent.getStringExtra("session") ?: "")))
    }
}

