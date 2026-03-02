package org.thoughtcrime.securesms.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired when the user swipes away (dismisses) a notification.
 * Dismissing does NOT mark the thread as read — the thread stays unread.
 */
class DeleteNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val DELETE_NOTIFICATION_ACTION = "network.loki.securesms.DELETE_NOTIFICATION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty — dismissing a notification should not mark the thread as read.
        // The thread's unread state is managed by lastSeen, which only advances when the user
        // explicitly reads the conversation or taps "Mark Read".
    }
}
