package org.thoughtcrime.securesms.notifications

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.util.AppVisibilityManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This class automatically schedules and cancels the background polling work based on the
 * visibility of the app and the availability of the logged in user.
 */
@OptIn(FlowPreview::class)
@Singleton
class BackgroundPollManager @Inject constructor(
    private val application: Application,
    private val appVisibilityManager: AppVisibilityManager,
) : AuthAwareComponent {
    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        appVisibilityManager.isAppVisible
            .debounce(1_000L)
            .distinctUntilChanged()
            .collectLatest { shouldSchedule ->
                if (shouldSchedule) {
                    Log.i(TAG, "Scheduling background polling work.")
                    BackgroundPollWorker.schedulePeriodic(application)
                } else {
                    Log.i(TAG, "Cancelling background polling work.")
                    BackgroundPollWorker.cancelPeriodic(application)
                }
            }
    }

    override fun onLoggedOut() {
        Log.i(TAG, "Cancelling background polling work on logout.")
        BackgroundPollWorker.cancelPeriodic(application)
    }

    class BootBroadcastReceiver : BroadcastReceiver() {
        @SuppressLint("UnsafeProtectedBroadcastReceiver")
        override fun onReceive(context: Context, intent: Intent) {
            // This broadcast receiver does nothing but to bring up the app,
            // once the app is up, the `BackgroundPollWorker` will have the chance to
            // schedule any background polling work accordingly
        }
    }

    companion object {
        private const val TAG = "BackgroundPollManager"
    }
}
