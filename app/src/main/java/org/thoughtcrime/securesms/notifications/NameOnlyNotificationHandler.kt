package org.thoughtcrime.securesms.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.merge
import org.thoughtcrime.securesms.conversation.v2.messages.MessageFormatter
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles notifications in [NotificationPrivacy.ShowNameOnly] mode.
 *
 * Shows one per-thread notification with the thread name and avatar, but replaces the message
 * body with a generic "You've got a new message" string. No reaction notifications are shown.
 * The [NotifyType.MENTIONS] per-thread filter is still respected.
 */
@Singleton
class NameOnlyNotificationHandler @Inject constructor(
    @ApplicationContext context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    recipientRepository: RecipientRepository,
    currentActivityObserver: CurrentActivityObserver,
    private val reactionDatabase: ReactionDatabase,
    private val messageFormatter: MessageFormatter,
    avatarUtils: AvatarUtils,
    avatarBitmapCache: AvatarBitmapCache,
    channels: NotificationChannelManager,
) : ThreadBasedNotificationHandler(
    context,
    currentActivityObserver,
    avatarUtils,
    channels,
    recipientRepository,
    avatarBitmapCache,
) {
    suspend fun process() {
        merge(
            threadDb.changeNotification,
            mmsDatabase.changeNotification,
            smsDatabase.changeNotification
        ).collect {
        }
    }

    companion object {
        private const val TAG = "NameOnlyNotificationHandler"
    }
}
