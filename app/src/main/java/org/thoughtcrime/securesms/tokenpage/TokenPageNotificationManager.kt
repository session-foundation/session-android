package org.thoughtcrime.securesms.tokenpage

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.utilities.TextSecurePreferences
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@Singleton
class TokenPageNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenDataManager: TokenDataManager,
    private val prefs: TextSecurePreferences
) {

    companion object {
        // WorkManager tags for the local-origin notification about network page
        const val productionNotificationWorkName = "SESSION_TOKEN_DROP_INITIAL_NOTIFICATION"
        const val debugNotificationWorkName      = "SESSION_TOKEN_DROP_DEBUG_NOTIFICATION"
    }

    // Method to schedule a notification to be shown at a specific time in the future.
    // IMPORTANT: If `constructDebugNotification` is true then we can schedule the notification over
    // and over (and we do so with a 10 second delay), however if it's not then we can only schedule
    // the notification once - which is what we want for production.
    fun scheduleTokenPageNotification(constructDebugNotification: Boolean) {
        // Bail early if we are this isn't a debug notification and we've already shown the notification
        if (prefs.hasSeenTokenPageNotification() && !constructDebugNotification) return

        // The notification is scheduled for 10 seconds after opening for debug notifications & 1 hour after opening for production
        val scheduleDelayMS = if (constructDebugNotification) {
            10.seconds.inWholeMilliseconds
        } else {
            1.hours.inWholeMilliseconds
        }

        // Create the one-time work request for our notification. If we are constructing a debug
        // notification we set the delay for 10 seconds and we DO NOT tag the notification..
        val notificationWork: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TokenDropNotificationWorker>()
                .setInitialDelay(scheduleDelayMS, TimeUnit.MILLISECONDS)
                .addTag(if (constructDebugNotification) debugNotificationWorkName else productionNotificationWorkName) // Add the tag to differentiate between a debug and a production notification!
                .build()

        // Either enqueue a debug notification if asked to (this can be shown multiple times)..
        if (constructDebugNotification) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                debugNotificationWorkName,
                ExistingWorkPolicy.REPLACE,
                notificationWork
            )
        } else {
            // ..or enqueue a production notification which is one-time only (i.e., it will not be updated should one already be enqueued) - and
            // ONLY do this if we haven't already shown the token page notification (there's no point in asking the work manager to show it again
            // - it won't because the ExistingWorkPolicy is KEEP).
            // Note: Should the device be powered off when the scheduled notification is due to fire then WorkManager will fire
            // the notification immediately on next boot (i.e., it won't get lost or forgotten).
            WorkManager.getInstance(context).enqueueUniqueWork(
                productionNotificationWorkName,
                ExistingWorkPolicy.KEEP,
                notificationWork
            )
        }
    }
}