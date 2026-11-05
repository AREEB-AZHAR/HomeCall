package dev.homecall.homecall

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 35], application = Application::class)
class HomeCallServiceTest {
    @Test
    fun ringingIsTheForegroundNotificationAndDeclineRestoresAvailability() {
        val service = Robolectric.buildService(HomeCallService::class.java).create().get()
        val shadow = shadowOf(service)
        service.onStartCommand(Intent().setAction("available"), 0, 1)
        service.onStartCommand(
            Intent().setAction("ring").putExtra("session", "first").putExtra("name", "Kitchen"),
            0,
            2,
        )
        val ringing = shadow.lastForegroundNotification
        assertEquals(21, shadow.lastForegroundNotificationId)
        assertEquals("ring", ringing.channelId)
        assertEquals(Notification.CATEGORY_CALL, ringing.category)
        assertTrue(ringing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(
                Notification.CallStyle::class.java.name,
                ringing.extras.getString(Notification.EXTRA_TEMPLATE),
            )
        }
        assertNull(
            shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(22)
        )
        service.onStartCommand(Intent().setAction("available"), 0, 3)
        assertEquals(21, shadow.lastForegroundNotificationId)
        assertEquals("available", shadow.lastForegroundNotification.channelId)
        assertNull(shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun answeringReplacesTheIncomingActionsWithAnOngoingCall() {
        val service = Robolectric.buildService(HomeCallService::class.java).create().get()
        val shadow = shadowOf(service)
        service.onStartCommand(
            Intent().setAction("ring").putExtra("session", "second").putExtra("name", "Bedroom"),
            0,
            1,
        )
        service.onStartCommand(
            Intent()
                .setAction("active")
                .putExtra("active", true)
                .putExtra("session", "second")
                .putExtra("name", "Bedroom"),
            0,
            2,
        )
        assertEquals(21, shadow.lastForegroundNotificationId)
        assertEquals("available", shadow.lastForegroundNotification.channelId)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(
                Notification.CallStyle::class.java.name,
                shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TEMPLATE),
            )
        }
        assertNull(
            shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(22)
        )
    }
}
