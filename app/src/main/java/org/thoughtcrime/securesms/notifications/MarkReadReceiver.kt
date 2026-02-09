package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.network.SnodeClock
import org.session.libsignal.utilities.Log
import javax.inject.Inject

@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    lateinit var clock: SnodeClock


    override fun onReceive(context: Context, intent: Intent) {
        if (CLEAR_ACTION != intent.action) return
        val threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA) ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(NOTIFICATION_ID_EXTRA, -1))
        GlobalScope.launch {
            val currentTime = clock.currentTimeMillis()
            threadIds.forEach {
                Log.i(TAG, "Marking as read: $it")
                storage.markConversationAsRead(
                    threadId = it,
                    lastSeenTime = currentTime,
                    force = true
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
