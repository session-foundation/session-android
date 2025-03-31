package org.thoughtcrime.securesms.webrtc

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge.Companion.ACTION_DENY_CALL
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge.Companion.ACTION_IGNORE_CALL
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge.Companion.ACTION_LOCAL_HANGUP

class CallNotificationBuilder {

    companion object {
        const val WEBRTC_NOTIFICATION = 313388

        const val TYPE_OUTGOING_RINGING    = 2
        const val TYPE_ESTABLISHED         = 3
        const val TYPE_INCOMING_CONNECTING = 4
        const val TYPE_INCOMING_PRE_OFFER  = 5

        @JvmStatic
        fun areNotificationsEnabled(context: Context): Boolean {
            val notificationManager = NotificationManagerCompat.from(context)
            return notificationManager.areNotificationsEnabled()
        }

        @JvmStatic
        fun getCallInProgressNotification(context: Context, type: Int, recipient: Recipient?): Notification {
            val contentIntent = WebRtcCallActivity.getCallActivityIntent(context)

            val pendingIntent = PendingIntent.getActivity(context, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(context, NotificationChannels.CALLS)
                    .setSound(null)
                    .setSmallIcon(R.drawable.ic_phone)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)

            var recipName = "Unknown"
            recipient?.name?.let { name ->
                recipName = name
            }

            builder.setContentTitle(recipName)

            when (type) {
                TYPE_INCOMING_PRE_OFFER -> {
                    val txt = Phrase.from(context, R.string.callsIncoming).put(NAME_KEY, recipName).format()
                    builder.setContentText(txt)
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                    builder.addAction(
                        getEndCallNotification(
                            context,
                            ACTION_DENY_CALL,
                            R.drawable.ic_x,
                            R.string.decline)
                    )
                    // If notifications aren't enabled, we will trigger the intent from WebRtcCallBridge
                    builder.setFullScreenIntent(getFullScreenPendingIntent(context), true)
                    builder.addAction(
                        getActivityNotificationAction(
                            context,
                            WebRtcCallActivity.ACTION_ANSWER,
                            R.drawable.ic_phone,
                            R.string.accept
                        )
                    )
                    builder.priority = NotificationCompat.PRIORITY_MAX
                    // catch the case where this notification is swiped off, to ignore the call
                    builder.setDeleteIntent(getEndCallPendingIntent(context, ACTION_IGNORE_CALL))
                    // remove notification if tapped on
                    builder.setAutoCancel(true)
                }

                TYPE_INCOMING_CONNECTING -> {
                    builder.setContentText(context.getString(R.string.callsConnecting))
                            .setSilent(true)
                }

                TYPE_OUTGOING_RINGING -> {
                    builder.setContentText(context.getString(R.string.callsConnecting))
                        .setSilent(true)
                    builder.addAction(
                        getEndCallNotification(
                            context,
                            ACTION_LOCAL_HANGUP,
                            R.drawable.ic_phone_fill_custom,
                            R.string.cancel
                        )
                    )
                }
                else -> {
                    builder.setContentText(context.getString(R.string.callsInProgress))
                        .setSilent(true)
                    builder.addAction(
                        getEndCallNotification(
                            context,
                            ACTION_LOCAL_HANGUP,
                            R.drawable.ic_phone_fill_custom,
                            R.string.callsEnd
                    )
                    ).setUsesChronometer(true)
                }
            }

            return builder.build()
        }

        private fun getFullScreenPendingIntent(context: Context): PendingIntent {
            val intent = WebRtcCallActivity.getCallActivityIntent(context)
                .setAction(WebRtcCallActivity.ACTION_FULL_SCREEN_INTENT)
            return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun getEndCallNotification(context: Context, action: String,
                                             @DrawableRes iconResId: Int, @StringRes titleResId: Int): NotificationCompat.Action {
            return NotificationCompat.Action(
                iconResId, context.getString(titleResId),
                getEndCallPendingIntent(context, action)
            )
        }

        private fun getEndCallPendingIntent(context: Context, action: String): PendingIntent{
            val actionIntent = Intent(context, EndCallReceiver::class.java).apply {
                this.action = action
                component = ComponentName(context, EndCallReceiver::class.java)
            }

            return PendingIntent.getBroadcast(context, 0, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun getActivityNotificationAction(context: Context, action: String,
                                                  @DrawableRes iconResId: Int, @StringRes titleResId: Int): NotificationCompat.Action {
            val intent = WebRtcCallActivity.getCallActivityIntent(context)
                    .setAction(action)

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            return NotificationCompat.Action(iconResId, context.getString(titleResId), pendingIntent)
        }

    }
}