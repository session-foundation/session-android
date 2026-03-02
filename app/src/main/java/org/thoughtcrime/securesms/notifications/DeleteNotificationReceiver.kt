package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.session.libsession.network.SnodeClock
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject

@AndroidEntryPoint
class DeleteNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val DELETE_NOTIFICATION_ACTION = "network.loki.securesms.DELETE_NOTIFICATION"
        const val EXTRA_IDS = "message_ids"
        const val EXTRA_MMS = "is_mms"
        const val EXTRA_THREAD_IDS = "thread_ids"
    }

    @Inject @ManagerScope
    lateinit var scope: CoroutineScope

    @Inject lateinit var storage: Storage
    @Inject lateinit var snodeClock: SnodeClock

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DELETE_NOTIFICATION_ACTION) return

        val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: return
        val mms = intent.getBooleanArrayExtra(EXTRA_MMS) ?: return
        val threadIds = intent.getLongArrayExtra(EXTRA_THREAD_IDS)?.toSet() ?: return

        if (ids.size != mms.size) return

        val pending = goAsync() // extends the receiver's lifecycle
        scope.launch {
            try {
                val now = snodeClock.currentTimeMillis()
                for (threadId in threadIds){
                    storage.updateConversationLastSeenIfNeeded(
                        threadId = threadId,
                        lastSeenTime = now
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
