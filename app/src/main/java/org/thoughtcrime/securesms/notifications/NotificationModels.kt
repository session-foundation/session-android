package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.core.content.IntentCompat
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v3.ConversationActivityV3
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.util.AvatarUIData
import java.time.Instant

internal data class Changes(
    val threadId: Long,
    val fromReaction: Boolean,
)

internal sealed interface NotificationMessageItem {
    val sentAt: Instant
    val authorName: String
    val authorAvatar: AvatarUIData
    val authorAddress: Address

    fun body(context: Context): CharSequence
}

internal data class MessageData(
    val id: MessageId,
    val body: CharSequence,
    override val sentAt: Instant,
    override val authorAddress: Address,
    override val authorName: String,
    override val authorAvatar: AvatarUIData,
) : NotificationMessageItem {
    override fun body(context: Context): CharSequence = body
}

internal data class ReactionData(
    val reactionId: Long,
    val emoji: String,
    override val authorAddress: Address,
    override val authorName: String,
    override val authorAvatar: AvatarUIData,
    override val sentAt: Instant,
) : NotificationMessageItem {
    override fun body(context: Context): CharSequence {
        return Phrase.from(context, R.string.emojiReactsNotification)
            .put(EMOJI_KEY, emoji)
            .format()
    }
}

internal data class MessageRequestData(
    override val sentAt: Instant,
    override val authorName: String,
    override val authorAvatar: AvatarUIData,
    override val authorAddress: Address,
) : NotificationMessageItem {
    override fun body(context: Context): CharSequence =
        context.getString(R.string.messageRequestsNew)
}


internal fun threadTag(threadId: Long): String = "thread-$threadId"

internal const val GLOBAL_NOTIFICATION_TAG = "global"

internal val ConversationActivityV2.threadAddress: Address.Conversable?
    get() = IntentCompat.getParcelableExtra(
        intent,
        ConversationActivityV2.ADDRESS,
        Address.Conversable::class.java
    )

internal val ConversationActivityV3.threadAddress: Address.Conversable?
    get() = IntentCompat.getParcelableExtra(
        intent,
        ConversationActivityV3.ADDRESS,
        Address.Conversable::class.java
    )
