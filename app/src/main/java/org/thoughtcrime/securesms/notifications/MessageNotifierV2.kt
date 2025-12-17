package org.thoughtcrime.securesms.notifications

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.util.timedBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotifierV2 @Inject constructor(
    application: Application,
    private val threadDatabase: ThreadDatabase,
    private val conversationRepository: ConversationRepository,
    private val currentActivityObserver: CurrentActivityObserver,
) : AuthAwareComponent {
    private val notificationManager by lazy {
        NotificationManagerCompat.from(application)
    }

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        supervisorScope {
            launch { processNewMessages() }
            launch { processLastSeenChanges() }
        }
    }

    private suspend fun processNewMessages() {
        threadDatabase
            .updateNotifications
            .timedBuffer(1000L, 50)
            .collect { threadIDs ->

            }
    }

    private suspend fun processLastSeenChanges() {

    }

    override fun onLoggedOut() {
        notificationManager.activeNotifications
            .forEach { notification ->
                if (notification.tag == NOTIFICATION_TAG) {
                     notificationManager.cancel(notification.id)
                }
            }
    }

    companion object {
        private const val TAG = "MessageNotifierV2"

        private const val NOTIFICATION_TAG = "thread_notification"
    }
}