package org.thoughtcrime.securesms.notifications

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getAndMarkAsNotified
import org.thoughtcrime.securesms.database.markAllNotified
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.util.get
import org.thoughtcrime.securesms.util.timedBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class MessageNotifierV2 @Inject constructor(
    application: Application,
    private val threadDatabase: ThreadDatabase,
    private val conversationRepository: ConversationRepository,
    private val currentActivityObserver: CurrentActivityObserver,
    private val configFactory: ConfigFactoryProtocol,
    private val mmsSmsDatabase: MmsSmsDatabase,
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

    private fun lastReadMapFlow() = conversationRepository
        .conversationListAddressesFlow
        .map { addresses ->
            configFactory.withUserConfigs { configs ->
                addresses.associateWith { address ->
                    configs.convoInfoVolatile.get(address)?.lastRead ?: 0L
                }
            }
        }
        .distinctUntilChanged()

    @OptIn(FlowPreview::class)
    private suspend fun processNewMessages() {
        combine(
            threadDatabase.updateNotifications.debounce(500.milliseconds),
            lastReadMapFlow(),
        ) { _, lastReadMap ->
            val records by lazy { mmsSmsDatabase.getAndMarkAsNotified(lastReadMap) }
            when (val currentActivity = currentActivityObserver.currentActivity.value) {
                // No notification for home screen
                is HomeActivity -> {
                    // Simply mark all messages as notified without retrieving them
                    mmsSmsDatabase.markAllNotified()
                    emptyMap()
                }

                // No notification for the currently open conversation
                is ConversationActivityV2 -> {
                    val currentShowingAddress = currentActivity.address
                    records.filterNot { (threadAddress, _) -> threadAddress == currentShowingAddress }
                }

                // Default case, show all notifications
                else -> records
            }
        }.collect { records ->
            Log.d(TAG, "Notifying for $records ")
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