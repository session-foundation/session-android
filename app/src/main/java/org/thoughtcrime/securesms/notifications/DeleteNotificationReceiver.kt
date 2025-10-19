package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
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

    @Inject lateinit var messageNotifier: MessageNotifier

    @Inject lateinit var smsDb: SmsDatabase
    @Inject lateinit var mmsDb: MmsDatabase
    @Inject lateinit var storage: Storage

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DELETE_NOTIFICATION_ACTION) return

        val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: return
        val mms = intent.getBooleanArrayExtra(EXTRA_MMS) ?: return
        val threadIds = intent.getLongArrayExtra(EXTRA_THREAD_IDS)?.toSet() ?: return

        if (ids.size != mms.size) return

        val pending = goAsync() // extends the receiver's lifecycle
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    for(threadId in threadIds){
                        storage.markConversationAsRead(
                            threadId = threadId,
                            lastSeenTime = now,
                            force = false,
                            updateNotification = false
                        )
                    }

                    for (i in ids.indices) {
                        if (!mms[i]) smsDb.markAsNotified(ids[i])
                        else mmsDb.markAsNotified(ids[i])
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
