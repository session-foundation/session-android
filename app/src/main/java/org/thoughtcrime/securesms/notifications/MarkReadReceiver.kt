package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject

@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    lateinit var clock: SnodeClock

    @Inject
    lateinit var threadDatabase: ThreadDatabase

    @Inject
    @ManagerScope
    lateinit var scope: CoroutineScope


    override fun onReceive(context: Context, intent: Intent) {
        if (CLEAR_ACTION != intent.action) return
        val threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA) ?: return

        // Use the latest message timestamp from the notification, falling back to current time
        val lastSeenTime = intent.getLongExtra(LATEST_TIMESTAMP_EXTRA, 0L)
            .takeIf { it > 0L }
            ?: clock.currentTimeMillis()

        // Notification cancellation is handled reactively by NotificationProcessor when lastSeen advances.
        scope.launch {
            threadIds.forEach {
                Log.i(TAG, "Marking as read: $it at timestamp $lastSeenTime")
                storage.updateConversationLastSeenIfNeeded(
                    threadAddress = threadDatabase.getRecipientForThreadId(it) as? Address.Conversable ?: return@forEach,
                    lastSeenTime = lastSeenTime,
                )
            }
        }
    }

    companion object {
        private val TAG = MarkReadReceiver::class.java.simpleName
        const val CLEAR_ACTION = "network.loki.securesms.notifications.CLEAR"
        const val THREAD_IDS_EXTRA = "thread_ids"
        const val LATEST_TIMESTAMP_EXTRA = "latest_timestamp"
    }
}
