package org.thoughtcrime.securesms.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject

/**
 * A [BroadcastReceiver] triggered when the user tap "mark as read" in the notification.
 */
@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    @ManagerScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (CLEAR_ACTION != intent.action) return

        val threadAddress = requireNotNull(IntentCompat.getParcelableExtra(intent, EXTRA_THREAD_ADDRESS, Address.Conversable::class.java)) {
            "Missing thread address"
        }

        val lastSeenTime = intent.getLongExtra(EXTRA_LATEST_MESSAGE_TIMESTAMP, 0L)
        val result = goAsync()

        scope.launch {
            try {
                storage.updateConversationLastSeenIfNeeded(threadAddress, lastSeenTime)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating conversation last seen", e)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        private const val TAG: String = "MarkReadReceiver"
        private const val CLEAR_ACTION = "network.loki.securesms.notifications.CLEAR"
        private const val EXTRA_THREAD_ADDRESS = "thread_address"
        private const val EXTRA_LATEST_MESSAGE_TIMESTAMP = "latest_timestamp"

        fun buildIntent(
            context: Context,
            threadAddress: Address.Conversable,
            latestMessageTimestampMs: Long
        ): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, MarkReadReceiver::class.java)
                    .setAction(CLEAR_ACTION)
                    .putExtra(EXTRA_THREAD_ADDRESS, threadAddress)
                    .putExtra(EXTRA_LATEST_MESSAGE_TIMESTAMP, latestMessageTimestampMs),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
