package dev.homecall.homecall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private val app get() = application as HomeCallApp
    override fun provideFlutterEngine(context: Context): FlutterEngine = app.engine
    override fun shouldDestroyEngineWithHost() = false
    override fun onResume() { super.onResume(); app.activity = this; handle(intent) }
    override fun onPause() { app.activity = null; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    private fun handle(intent: Intent?) {
        if(intent?.action == "answer" && app.activity != null) {
            intent.action = "open"
            app.event("answer",mapOf("session" to (intent.getStringExtra("session") ?: "")))
        }
    }
    fun permissions() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 610)
    }
}
