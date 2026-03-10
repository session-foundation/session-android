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
    @param:ApplicationContext private val context: Context,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val recipientRepository: RecipientRepository,
    private val currentActivityObserver: CurrentActivityObserver,
    private val reactionDatabase: ReactionDatabase,
    private val messageFormatter: MessageFormatter,
    private val avatarUtils: AvatarUtils,
    private val avatarBitmapCache: AvatarBitmapCache,
    private val channels: NotificationChannelManager,
) {
    private val notificationManager: NotificationManagerCompat get() =
        NotificationManagerCompat.from(context)

    private val currentActivity get() = currentActivityObserver.currentActivity.value

    suspend fun process() {
    }


    companion object {
        private const val TAG = "NameOnlyNotificationHandler"
    }
}
