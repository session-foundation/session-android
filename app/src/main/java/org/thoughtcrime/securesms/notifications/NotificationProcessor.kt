package org.thoughtcrime.securesms.notifications

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.supervisorScope
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.notifications.NotificationPreferences.PRIVACY
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive notification processor that replaces the poll-based DefaultMessageNotifier.
 *
 * Watches the user's notification privacy preference and delegates to the appropriate handler:
 * - [NotificationPrivacy.ShowNameAndContent] → [FullNotificationHandler]
 * - [NotificationPrivacy.ShowNameOnly]       → [NameOnlyNotificationHandler]
 * - [NotificationPrivacy.ShowNoNameOrContent] → [NoNameOrContentNotificationHandler]
 *
 * Key behaviours:
 * - A message is "new" purely if `dateSent > thread.lastSeen`
 * - Dismissing a notification does NOT mark the thread as read
 * - "Mark Read" sets `lastSeen` to the latest message's `dateSent`
 * - When `lastSeen` advances, stale notifications auto-cancel
 */
@Singleton
class NotificationProcessor @Inject constructor(
    private val prefs: PreferenceStorage,
    private val fullHandler: FullNotificationHandler,
    private val nameOnlyHandler: NameOnlyNotificationHandler,
    private val noNameOrContentHandler: NoNameOrContentNotificationHandler,
) : AuthAwareComponent {

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState): Unit = supervisorScope {
        prefs.watch(this, PRIVACY)
            .collectLatest { privacy ->
                Log.d(TAG, "Start processing notification for $privacy")
                when (privacy) {
                    NotificationPrivacy.ShowNameAndContent -> fullHandler.process()
                    NotificationPrivacy.ShowNameOnly -> nameOnlyHandler.process()
                    NotificationPrivacy.ShowNoNameOrContent -> noNameOrContentHandler.process()
                }
            }
    }

    companion object {
        private const val TAG = "NotificationProcessor"
    }
}
