package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.ThreadDatabase
import javax.inject.Inject

@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    lateinit var clock: SnodeClock

    @Inject
    lateinit var threadDatabase: ThreadDatabase


    override fun onReceive(context: Context, intent: Intent) {
        if (CLEAR_ACTION != intent.action) return
        val threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA) ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(NOTIFICATION_ID_EXTRA, -1))
        GlobalScope.launch {
            val currentTime = clock.currentTimeMills()
            threadIds.forEach {
                Log.i(TAG, "Marking as read: $it")
                storage.updateConversationLastSeenIfNeeded(
                    threadAddress = threadDatabase.getRecipientForThreadId(it) as? Address.Conversable ?: return@forEach,
                    lastSeenTime = currentTime,
                )
            }
        }
    }

    companion object {
        private val TAG = MarkReadReceiver::class.java.simpleName
        const val CLEAR_ACTION = "network.loki.securesms.notifications.CLEAR"
        const val THREAD_IDS_EXTRA = "thread_ids"
        const val NOTIFICATION_ID_EXTRA = "notification_id"

    }
}
