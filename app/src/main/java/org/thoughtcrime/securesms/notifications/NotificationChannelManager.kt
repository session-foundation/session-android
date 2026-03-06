package org.thoughtcrime.securesms.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.R
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationChannelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: PreferenceStorage,
): OnAppStartupComponent {
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        if (!prefs[MIGRATED_FROM_OLD_CHANNELS]) {
            recreateChannel(true)
            prefs[MIGRATED_FROM_OLD_CHANNELS] = true
        } else {
            recreateChannel(false)
        }
    }

    private fun recreateChannel(migrateFromOldChannels: Boolean) {
        val channels = ChannelDescription
            .entries
            .map { desc ->
                val existingChannel = if (migrateFromOldChannels)
                    notificationManager.getNotificationChannel(desc.migratingFromOldId)
                else null

                NotificationChannel(
                    desc.systemId,
                    context.getText(desc.settingsName),
                    desc.importance,
                ).also { ch ->
                    if (existingChannel != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 30 && existingChannel.hasUserSetSound()) {
                            ch.setSound(existingChannel.sound, null)
                        }

                        ch.lightColor = existingChannel.lightColor
                        ch.enableVibration(existingChannel.shouldVibrate())
                        ch.lockscreenVisibility = existingChannel.lockscreenVisibility

                        if (android.os.Build.VERSION.SDK_INT >= 35) {
                            ch.vibrationEffect = existingChannel.vibrationEffect
                        }
                    }
                }
            }

        notificationManager.createNotificationChannels(channels)
    }

    override fun onPostAppStarted() {
    }

    fun onLocaleChanged() {
        recreateChannel(false)
    }

    fun getNotificationChannelId(desc: ChannelDescription): String {
        return desc.systemId
    }

    enum class ChannelDescription(@get:StringRes val settingsName: Int, val importance: Int) {
        ONE_TO_ONE_MESSAGES(R.string.sessionConversations, NotificationManager.IMPORTANCE_HIGH),
        GROUP_MESSAGES(R.string.conversationsGroups, NotificationManager.IMPORTANCE_DEFAULT),
        COMMUNITY_MESSAGES(R.string.conversationsCommunities, NotificationManager.IMPORTANCE_DEFAULT),
        CALLS(R.string.call, NotificationManager.IMPORTANCE_HIGH),
        LOCK_STATUS(R.string.lockAppStatus, NotificationManager.IMPORTANCE_LOW),
    }

    private val ChannelDescription.systemId: String
        get() = when (this) {
            ChannelDescription.ONE_TO_ONE_MESSAGES -> "notification.channel.1o1"
            ChannelDescription.GROUP_MESSAGES -> "notification.channel.group"
            ChannelDescription.COMMUNITY_MESSAGES -> "notification.channel.community"
            ChannelDescription.CALLS -> "notification.channel.calls"
            ChannelDescription.LOCK_STATUS -> "notification.channel.lock_status"
        }

    private val ChannelDescription.migratingFromOldId: String
        get() = when (this) {
            ChannelDescription.ONE_TO_ONE_MESSAGES -> "messages_3"
            ChannelDescription.GROUP_MESSAGES -> "messages_3"
            ChannelDescription.COMMUNITY_MESSAGES -> "messages_3"
            ChannelDescription.CALLS -> "calls_v3"
            ChannelDescription.LOCK_STATUS -> "locked_status_v2"
        }

    companion object {
        private val MIGRATED_FROM_OLD_CHANNELS = PreferenceKey.boolean("notifications.channels.migrated")
    }
}