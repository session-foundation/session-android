package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles notifications in [NotificationPrivacy.ShowNoNameOrContent] mode.
 *
 * Shows a single global notification ("You've got a new message.") whenever any thread has
 * unread messages, with no thread name or message content exposed. The notification is
 * suppressed when the home screen is in the foreground. Per-thread [NotifyType] filters
 * are still respected.
 */
@Singleton
class NoNameOrContentNotificationHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val recipientRepository: RecipientRepository,
    private val currentActivityObserver: CurrentActivityObserver,
    private val reactionDatabase: ReactionDatabase,
    private val messageFormatter: MessageFormatter,
    private val channels: NotificationChannelManager,
) {
    private val notificationManager: NotificationManagerCompat get() =
        NotificationManagerCompat.from(context)

    private val currentActivity get() = currentActivityObserver.currentActivity.value

    suspend fun process() {

    }

    companion object {
        private const val TAG = "NoNameOrContentNotificationHandler"
    }
}
