package org.thoughtcrime.securesms.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage

@RunWith(RobolectricTestRunner::class)
class NotificationChannelManagerTest {

    private val notificationManager = mockk<NotificationManager>(relaxed = true) {
        every { getNotificationChannel(any()) } returns null
    }

    private val context = mockk<Context> {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        every { getText(any()) } returns "Channel"
    }

    private val prefs = mockk<PreferenceStorage>(relaxed = true) {
        every { get(any<PreferenceKey<Boolean>>()) } returns false
    }

    private fun channel(id: String) =
        NotificationChannel(id, id, NotificationManager.IMPORTANCE_DEFAULT)

    @Test
    fun `a channel the system refuses to delete leaves the migration for a later launch`() {
        every { notificationManager.notificationChannels } returns
                listOf(channel("messages_3"), channel("locked_status_v2"))
        every { notificationManager.deleteNotificationChannel("locked_status_v2") } throws
                SecurityException("Channel has a FGS")

        NotificationChannelManager(context, prefs)

        verify { notificationManager.deleteNotificationChannel("messages_3") }
        verify { notificationManager.createNotificationChannels(any()) }
        verify(exactly = 0) { prefs[any<PreferenceKey<Boolean>>()] = true }
    }

    @Test
    fun `a retried migration deletes only the old channels`() {
        every { notificationManager.notificationChannels } returns
                listOf(channel("notification.channel.1o1"), channel("calls_v3"))

        NotificationChannelManager(context, prefs)

        verify(exactly = 0) { notificationManager.deleteNotificationChannel("notification.channel.1o1") }
        verify { notificationManager.deleteNotificationChannel("calls_v3") }
        verify { prefs[any<PreferenceKey<Boolean>>()] = true }
    }
}
