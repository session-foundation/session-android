package org.thoughtcrime.securesms.tokenpage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.getString
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants.NETWORK_NAME
import org.session.libsession.utilities.NonTranslatableStringConstants.TOKEN_NAME_LONG
import org.session.libsession.utilities.StringSubstitutionConstants.NETWORK_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TOKEN_NAME_LONG_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.preferences.SettingsActivity

@HiltWorker
class TokenDropNotificationWorker
@AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val prefs: TextSecurePreferences,
    private val tokenDataManager: TokenDataManager
) : CoroutineWorker(context, workerParams) {

    private val NOTIFICATION_CHANNEL_ID = "SessionTokenNotifications"
    private val NOTIFICATION_CHANNEL_NAME = "Session Token Notifications"
    private val TOKEN_NOTIFICATION_ID = 777

    override suspend fun doWork(): Result {
        val isDebugNotification =
            workerParams.tags.contains(TokenPageNotificationManager.debugNotificationWorkName)
        val alreadyShownTokenPageNotification = prefs.hasSeenTokenPageNotification()

        tokenDataManager.fetchInfoDataIfNeeded()

        // If this is a proper notification (not a debug one) and we've already
        // shown it then early exit rather than attempting to schedule another
        if (
            !isDebugNotification &&
            (alreadyShownTokenPageNotification)
        ) {
            return Result.success()
        }

        // Create the notification
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create a notification channel
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Session Token Page Notification Channel"

            // Typically Android will not allow a notification to display if the app is open in the foreground. To get around that and
            // show a "heads-up" notification that WILL display in the foreground we need to specifically add either a sound or a
            // vibration to it. In this case, we're specifically adding the default notification sound as a sound, which allows the
            // heads-up notification to show even when the app is already open.
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(
                soundUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        notificationManager.createNotificationChannel(channel)

        // Create an intent to open the TokenPageActivity when the notification is clicked
        val tokenPageActivityIntent = Intent(context, TokenPageActivity::class.java).apply {
            // Add flags to handle existing activity instances
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or // Necessary when starting an activity from a non-activity context (e.g. from a notification)
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or // If the activity is already running then bring it to the front and clear all other activities on top of it
                        Intent.FLAG_ACTIVITY_SINGLE_TOP    // Prevent the creation of a new instance if the activity is already at the top of the stack
        }

        // Use TaskStackBuilder to build the back stack - without this if we schedule a notification and then
        // with the app closed we click on it it takes us directly to the Token Page, but when we click the back
        // button it closed the app because we don't have a back-stack!
        // Note: I did try adding PARENT_ACTIVITY meta-data to the manifest to auto-generate the back-stack but
        // it didn't work so I've just hard-coded the back-stack here as there's only a single path to the token
        // page activity.
        val stackBuilder = TaskStackBuilder.create(context).apply {
            addNextIntent(Intent(context, HomeActivity::class.java))
            addNextIntent(Intent(context, SettingsActivity::class.java))
            addNextIntent(tokenPageActivityIntent)
        }

        val pendingIntent = stackBuilder.getPendingIntent(
            0,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationTxt = Phrase.from(applicationContext, R.string.sessionNetworkNotificationLive)
            .put(TOKEN_NAME_LONG_KEY, TOKEN_NAME_LONG)
            .put(NETWORK_NAME_KEY, NETWORK_NAME)
            .put(TOKEN_NAME_LONG_KEY, TOKEN_NAME_LONG)
            .format().toString()
        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.textsecure_primary))
            .setContentTitle(getString(context, R.string.app_name))
            .setContentText(notificationTxt)

            // Without setting a `BigTextStyle` on the notification we only see a single line that gets ellipsized at the edge of the screen
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notificationTxt)
            )

            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Automatically dismiss the notification when tapped

        // Show the notification & update the shared preference to record the fact that we've done so
        notificationManager.notify(TOKEN_NOTIFICATION_ID, builder.build())

        // Update our preference data to indicate we've now shown the notification if this isn't a debug / test notification
        if (!isDebugNotification) {
            prefs.setHasSeenTokenPageNotification(true)
        }

        return Result.success()
    }
}