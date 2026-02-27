package org.thoughtcrime.securesms.conversation.v3

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.text.AnnotatedString
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.R
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.thoughtcrime.securesms.conversation.v3.compose.message.Audio
import org.thoughtcrime.securesms.conversation.v3.compose.message.ClusterPosition
import org.thoughtcrime.securesms.conversation.v3.compose.message.Document
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageAvatar
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageLinkData
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageMediaItem
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageQuote
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageQuoteIcon
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageType
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewData
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewStatus
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewStatusIcon
import org.thoughtcrime.securesms.conversation.v3.compose.message.ReactionItem
import org.thoughtcrime.securesms.conversation.v3.compose.message.ReactionViewState
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs


@Singleton
class ConversationDataMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val dateUtils: DateUtils
) {
    private val timeZoneOffsetSeconds = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    sealed interface ConversationItem {
        data class Message(val data: MessageViewData) : ConversationItem
        data class DateBreak(val date: String) : ConversationItem
        data class UnreadMarker(val count: Int) : ConversationItem
    }

    fun map(
        record: MessageRecord,
        previous: MessageRecord?,
        next: MessageRecord?,
        threadRecipient: Recipient,
        localUserAddress: String,
        highlightKey: Any? = null,
        showStatus: Boolean = false,
    ): List<ConversationItem> {
        val isOutgoing = record.isOutgoing
        val senderName = record.individualRecipient.displayName()
        val isGroup = threadRecipient.isGroupOrCommunityRecipient

        val isStart = isStartOfCluster(record, previous, isGroup)
        val isEnd = isEndOfCluster(record, next, isGroup)

        val clusterPosition = when {
            isStart && isEnd -> ClusterPosition.ISOLATED
            isStart -> ClusterPosition.TOP
            isEnd -> ClusterPosition.BOTTOM
            else -> ClusterPosition.MIDDLE
        }

        val avatar = when{
            // outgoing and non group conversations: No avatar
            isOutgoing || !isGroup -> MessageAvatar.None

            // if at the right cluster position, show avatar
            clusterPosition == ClusterPosition.BOTTOM
                    || clusterPosition == ClusterPosition.ISOLATED -> MessageAvatar.Visible(avatarUtils.getUIDataFromRecipient(record.individualRecipient))

            // otherwise leave an empty space the size of the avatar
            else -> MessageAvatar.Invisible
        }

        val showDateBreak = shouldShowDateBreak(record, previous)
        val showAuthorName = shouldShowAuthorName(record, previous, isGroup, showDateBreak)

        val message = ConversationItem.Message(
            MessageViewData(
                id = record.messageId,
                type = mapMessageType(record, isOutgoing),
                author = senderName,
                displayName = showAuthorName,
                avatar = avatar,
                status = if (showStatus && isOutgoing) mapStatus(record) else null,
                quote = mapQuote(record),
                link = mapLinkPreview(record),
                reactionsState = mapReactions(record, localUserAddress),
                highlightKey = highlightKey,
        ))

        return buildList {
            add(message)

            // Items added after message appear visually ABOVE it (with reverseLayout = true)
            if (showDateBreak) add(ConversationItem.DateBreak(
                dateUtils.getDisplayFormattedTimeSpanString(record.timestamp)
            ))
        }
    }

    private fun isStartOfCluster(current: MessageRecord, previous: MessageRecord?, isGroupThread: Boolean): Boolean =
        previous == null || previous.isControlMessage || !dateUtils.isSameHour(current.timestamp, previous.timestamp) || if (isGroupThread) {
            current.recipient.address != previous.recipient.address
        } else {
            current.isOutgoing != previous.isOutgoing
        }

    private fun isEndOfCluster(current: MessageRecord, next: MessageRecord?, isGroupThread: Boolean): Boolean =
        next == null || next.isControlMessage || !dateUtils.isSameHour(current.timestamp, next.timestamp) || if (isGroupThread) {
            current.recipient.address != next.recipient.address
        } else {
            current.isOutgoing != next.isOutgoing
        }

    private fun shouldShowDateBreak(current: MessageRecord, previous: MessageRecord?): Boolean {
        // Always show before the first visible message (no previous)
        if (previous == null) return true

        // Never show before control messages
        if (current.isControlMessage) return false

        // Always show before a message that follows a control message
        if (previous.isControlMessage) return true

        val t1 = previous.timestamp
        val t2 = current.timestamp

        // Rule 1: 5+ minute gap
        if (abs(t2 - t1) > 5 * 60 * 1000) return true

        // Rule 2: crossed midnight in local timezone
        val day1 = ((t1 / 1000) + timeZoneOffsetSeconds) / 86400
        val day2 = ((t2 / 1000) + timeZoneOffsetSeconds) / 86400

        return day1 != day2
    }

    private fun shouldShowAuthorName(
        current: MessageRecord,
        previous: MessageRecord?,
        isGroupThread: Boolean,
        showDateBreak: Boolean,
    ): Boolean {
        if (!isGroupThread) return false
        if (current.isOutgoing) return false

        // Show if there's a date break, the author changed, or previous was a control message
        return (showDateBreak
                || current.individualRecipient.address != previous?.individualRecipient?.address)
                || previous.isControlMessage
    }

    // ---- Message type ----

    private fun mapMessageType(record: MessageRecord, isOutgoing: Boolean): MessageType {
        val mms = record as? MmsMessageRecord

        // Deleted messages — check first; body is not meaningful for these
        if (record.isDeleted) {
            return MessageType.Text(
                outgoing = isOutgoing,
                text = AnnotatedString(context.getString(R.string.deleteMessageDeletedGlobally)),
            )
        }

        // Audio
        //todo convov3 maybe this should be packed inside an audio composable to live listen to changes locally?
        val audioSlide = mms?.slideDeck?.audioSlide
        if (audioSlide != null) {
            return Audio(
                outgoing = isOutgoing,
                title = audioSlide.filename, // todo CONVOv3: drive from playback state
                speedText = "1x",             // todo CONVOv3: drive from playback state
                remainingText = "",           // todo CONVOv3: drive from playback state
                durationMs = 0L,             // todo CONVOv3: resolve from audio metadata
                positionMs = 0L,             // todo CONVOv3: drive from playback state
                isPlaying = false,           // todo CONVOv3: drive from playback state
                showLoader = audioSlide.isInProgress || audioSlide.isPendingDownload,
                text = record.body.takeIf { it.isNotBlank() }?.let { AnnotatedString(it) },
            )
        }

        // Document
        val documentSlide = mms?.slideDeck?.documentSlide
        if (documentSlide != null) {
            return Document(
                outgoing = isOutgoing,
                name = documentSlide.filename,
                size = Formatter.formatFileSize(context, documentSlide.fileSize),
                loading = documentSlide.isInProgress || documentSlide.isPendingDownload,
                uri = documentSlide.uri?.toString() ?: "",
                text = record.body.takeIf { it.isNotBlank() }?.let { AnnotatedString(it) },
            )
        }

        // Images + video — MediaMmsMessageRecord specifically holds downloaded media
        if (record is MediaMmsMessageRecord) {
            val mediaSlides = record.slideDeck.slides.filter { it.hasImage() || it.hasVideo() }

            //todp convoV3 map this properly
            if (mediaSlides.isNotEmpty()) {
                val items = mediaSlides.map { slide ->
                    val uri = (slide.uri ?: slide.thumbnailUri) ?: "".toUri()
                    val filename = slide.filename
                    val loading = slide.isInProgress || slide.isPendingDownload
                    val width = 100
                    val height =  100

                    if (slide.hasVideo()) {
                        MessageMediaItem.Video(uri, filename, loading, width, height)
                    } else {
                        MessageMediaItem.Image(uri, filename, loading, width, height)
                    }
                }
                return MessageType.Media(
                    outgoing = isOutgoing,
                    items = items,
                    loading = items.any { it.loading },
                    text = record.body.takeIf { it.isNotBlank() }?.let { AnnotatedString(it) },
                )
            }
        }

        // Plain text
        // todo CONVOv3: replace with spans for mentions, links, and markdown-style formatting
        return MessageType.Text(
            outgoing = isOutgoing,
            text = AnnotatedString(record.body),
        )
    }

    // ---- Quote ----
// todo CONVOv3: sort out properly
    private fun mapQuote(record: MessageRecord): MessageQuote? {
        val quote = (record as? MmsMessageRecord)?.quote ?: return null

        val icon: MessageQuoteIcon = MessageQuoteIcon.Bar
        /*when {
            quote.attachment.thumbnail != null -> MessageQuoteIcon.Image(
                uri = quote.attachment.thumbnail!!.uri?.toUri() ?: "".toUri(),
                filename = quote.attachment.fileName ?: "",
            )

            else -> MessageQuoteIcon.Bar
        }*/

        return MessageQuote(
            title = quote.author.displayName(),
            subtitle = quote.text?.ifBlank { null }
                ?: context.getString(R.string.document),
            icon = icon,
        )
    }

    // ---- Link preview ----

    private fun mapLinkPreview(record: MessageRecord): MessageLinkData? {
        val preview = (record as? MmsMessageRecord)
            ?.linkPreviews
            ?.firstOrNull()
            ?: return null

        return MessageLinkData(
            url = preview.url,
            title = preview.title,
            imageUri = preview.thumbnail?.thumbnailUri?.toString(),
        )
    }

    // ---- Reactions ----

    private fun mapReactions(record: MessageRecord, localUserAddress: String): ReactionViewState? {
        val reactions = record.reactions.ifEmpty { return null }

        // Per ReactionRecord docs:
        // - Community: count is the TOTAL for that emoji, same value on every record — use max
        // - Private/group: count must be SUMMED across records for the same emoji
        val isCommunity = record.recipient.isCommunityRecipient

        val items = reactions
            .groupBy { it.emoji }
            .entries
            .sortedBy { (_, group) -> group.minOf(ReactionRecord::sortId) }
            .map { (emoji, group) ->
                val count = if (isCommunity) {
                    group.maxOf { it.count }
                } else {
                    group.sumOf { it.count }
                }
                ReactionItem(
                    emoji = emoji,
                    count = count.toInt(),
                    selected = group.any { it.author == localUserAddress },
                )
            }

        return ReactionViewState(
            reactions = items,
            isExtended = false,        // todo CONVOv3: drive from per-message expanded state in ViewModel
            onReactionClick = {},
            onReactionLongClick = {},
            onShowMoreClick = {},
        )
    }

    // ---- Status ----

    // todo convov3 map properly
    private fun mapStatus(record: MessageRecord): MessageViewStatus? {
        return when {
            record.isFailed -> MessageViewStatus(
                name = context.getString(R.string.messageStatusFailedToSend),
                icon = MessageViewStatusIcon.DrawableIcon(R.drawable.ic_triangle_alert),
            )
            // isSending() = isOutgoing() && !isSent() — BASE_SENDING_TYPE / BASE_OUTBOX_TYPE
            record.isSending -> MessageViewStatus(
                name = context.getString(R.string.sending),
                icon = MessageViewStatusIcon.DrawableIcon(R.drawable.ic_circle_dots_custom),
            )
            record.isRead -> MessageViewStatus(
                name = context.getString(R.string.read),
                icon = MessageViewStatusIcon.DrawableIcon(R.drawable.ic_circle_check),
            )
            record.isSent -> MessageViewStatus(
                name = context.getString(R.string.sent),
                icon = MessageViewStatusIcon.DrawableIcon(R.drawable.ic_circle_check),
            )
            else -> null
        }
    }
}