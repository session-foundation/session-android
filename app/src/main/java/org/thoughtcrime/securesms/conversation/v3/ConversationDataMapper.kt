package org.thoughtcrime.securesms.conversation.v3

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.text.AnnotatedString
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import network.loki.messenger.R
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.truncatedForDisplay
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.conversation.v3.compose.message.AudioMessageData
import org.thoughtcrime.securesms.conversation.v3.compose.message.ClusterPosition
import org.thoughtcrime.securesms.conversation.v3.compose.message.DocumentMessageData
import org.thoughtcrime.securesms.conversation.v3.compose.message.HighlightMessage
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageAvatar
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageContent
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageContentData
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageContentGroup
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageContentPadding
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageLayout
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageLinkData
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageMediaItem
import org.thoughtcrime.securesms.conversation.v3.compose.message.QuoteMessageData
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageQuoteIcon
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewData
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewStatus
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewStatusIcon
import org.thoughtcrime.securesms.conversation.v3.compose.message.ReactionItem
import org.thoughtcrime.securesms.conversation.v3.compose.message.ReactionViewState
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.mutableListOf
import kotlin.math.abs


@Singleton
class ConversationDataMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val dateUtils: DateUtils,
    private val json: Json,
    private val recipientRepository: RecipientRepository
) {
    private val timeZoneOffsetSeconds = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    sealed interface ConversationItem {
        data class Message(val data: MessageViewData) : ConversationItem
        data class DateBreak(val messageId: MessageId, val date: String) : ConversationItem
        data object UnreadMarker : ConversationItem
    }

    fun map(
        record: MessageRecord,
        previous: MessageRecord?,
        next: MessageRecord?,
        threadRecipient: Recipient,
        localUserAddress: String,
        lastSeen: Long?,
        showStatus: Boolean = false,
        out: MutableList<ConversationItem>,
    ) {
        val isOutgoing = record.isOutgoing

        val layout = when {
            record.isControlMessage -> MessageLayout.CONTROL
            isOutgoing -> MessageLayout.OUTGOING
            else -> MessageLayout.INCOMING
        }

        val senderName = record.individualRecipient.displayName()
        val extraDisplayName = when {
            record.recipient.address is Address.Blinded ->
                (record.recipient.address as Address.Blinded).blindedId.truncatedForDisplay()

            else -> null
        }

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
                layout = layout,
                displayName = senderName,
                displayNameExtra = extraDisplayName,
                showDisplayName = showAuthorName,
                showProBadge = record.recipient.shouldShowProBadge,
                avatar = avatar,
                contentGroups = mapContentGroups(record),
                status = if (showStatus && isOutgoing) mapStatus(record) else null,
                reactions = mapReactions(record, localUserAddress),
                clusterPosition = clusterPosition
            ))

        val showUnreadMarker = lastSeen != null
                && record.timestamp > lastSeen
                && (previous == null || previous.timestamp <= lastSeen)
                && !record.isOutgoing

        out += message

        // Items added after message appear visually ABOVE it (with reverseLayout = true)
        if (showDateBreak) out += ConversationItem.DateBreak(
            messageId = message.data.id,
            date = dateUtils.getDisplayFormattedTimeSpanString(record.timestamp)
        )

        // unread marker, if needed
        //todo convov3 it seems this is always added on the last message instead of higher up when needed
        if (showUnreadMarker) out += ConversationItem.UnreadMarker
    }

    private fun isStartOfCluster(current: MessageRecord, previous: MessageRecord?, isGroupThread: Boolean): Boolean =
        previous == null || previous.isControlMessage || !dateUtils.isSameHour(current.timestamp, previous.timestamp) || if (isGroupThread) {
            current.recipient.address != previous.recipient.address
        } else {
            current.isOutgoing != previous.isOutgoing
        }

    private fun isEndOfCluster(current: MessageRecord, next: MessageRecord?, isGroupThread: Boolean): Boolean {
        if (next == null || next.isControlMessage) return true

        // If there's a date break before the next message, this is the end of a cluster
        if (shouldShowDateBreak(next, current)) return true

        return if (isGroupThread) {
            current.recipient.address != next.recipient.address
        } else {
            current.isOutgoing != next.isOutgoing
        }
    }

    private fun shouldShowDateBreak(current: MessageRecord, previous: MessageRecord?): Boolean {
        // Always show before the first visible message (no previous)
        if (previous == null) return true

        val t1 = previous.timestamp
        val t2 = current.timestamp

        // Rule 1: 5+ minute gap
        if (abs(t2 - t1) > 5 * 60 * 1000) return true

        // Rule 2: crossed midnight in local timezone
        return !dateUtils.isSameDay(t1, t2)
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

    // ---- Message content ----

    private fun mapContentGroups(record: MessageRecord): List<MessageContentGroup> {
        val groups = mutableListOf<MessageContentGroup>()
        val mms = record as? MmsMessageRecord

        // Special cases - which preclude other content
        // Deleted messages — check first; body is not meaningful for these
        if (record.isDeleted) {
            addContentToGroup(
                groups,
                MessageContentData.Text(
                    text = AnnotatedString(context.getString(R.string.deleteMessageDeletedGlobally)),
                )
            )

            return groups
        }

        // community invites
        if (record.isOpenGroupInvitation) {
            val jsonData = UpdateMessageData.fromJSON(json, record.body)
            if (jsonData?.kind is UpdateMessageData.Kind.OpenGroupInvitation) {
                addContentToGroup(
                    groups,
                    MessageContentData.CommunityInvite(
                        jsonData.kind.groupName,
                        jsonData.kind.groupUrl
                    ))

                return groups
            }
        }

        // Group 1: Quotes, Links, and Text
        // We map the message content data first
        val primaryData = mutableListOf<MessageContentData>()

        mapQuote(record)?.let { primaryData += MessageContentData.Quote(it) }
        mapLinkPreview(record)?.let { primaryData += MessageContentData.Link(it) }

        if (record.body.isNotBlank()) {

            val parsed = MentionUtilities.parseAndSubstituteMentions(
                recipientRepository = recipientRepository,
                input = record.body,
                context = context
            )
            val annotatedBody =  MessageTextFormatter.formatMessage(
                parsed = parsed,
                isOutgoing = record.isOutgoing,
            )

            primaryData += MessageContentData.Text(annotatedBody)
        }

        // now we can map the message content data to message content, which is a wrapper
        // that allows custom padding based on certain rules
        // for example used by quotes to change their paddings depending on neighboring content
        if (primaryData.isNotEmpty()) {
            val primaryContents: List<MessageContent> =
                primaryData.mapIndexed { index, data ->
                    val extraPadding =
                        if (data is MessageContentData.Quote) {
                            // custom rules for quotes
                            // add bottom padding if quote is alone or if there is a link below
                            val isAlone = primaryData.size == 1
                            val nextIsLink = primaryData.getOrNull(index + 1) is MessageContentData.Link

                            if (isAlone || nextIsLink) MessageContentPadding.Bottom else MessageContentPadding.None
                        } else {
                            MessageContentPadding.None
                        }

                    MessageContent(contentData = data, extraPadding = extraPadding)
                }

            groups.add(MessageContentGroup(primaryContents, showBubble = true))
        }

        // Group 2: Media, Audio, or Documents
        val audioSlide = mms?.slideDeck?.audioSlide
        if (audioSlide != null) {
            // Audio
            //todo convov3 maybe this should be packed inside an audio composable to live listen to changes locally?
            // todo CONVOv3: drive values from playback state
            addContentToGroup(
                groups,
                MessageContentData.Audio(
                    AudioMessageData(
                        title = audioSlide.filename,
                        speedText = "1x",
                        remainingText = "",
                        durationMs = 0L,
                        positionMs = 0L,
                        isPlaying = false,
                        showLoader = audioSlide.isInProgress
                    ))
            )
        }

        val documentSlide = mms?.slideDeck?.documentSlide
        if (documentSlide != null) {
            addContentToGroup(
                groups,
                MessageContentData.Document(DocumentMessageData(
                    name = documentSlide.filename,
                    size = Formatter.formatFileSize(context, documentSlide.fileSize),
                    uri = documentSlide.uri?.toString() ?: "",
                    loading = documentSlide.isInProgress
                ))
            )
        }

        if (record is MediaMmsMessageRecord) {
            val mediaSlides = record.slideDeck.slides.filter { it.hasImage() || it.hasVideo() }
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
                groups.add(MessageContentGroup(listOf(MessageContent(
                    MessageContentData.Media(items, items.any { it.loading })))
                    , showBubble = false)
                )
            }
        }

        return groups
    }

    private fun addContentToGroup(
        groups: MutableList<MessageContentGroup>,
        contentData: MessageContentData,
        showBubble: Boolean = true,
        paddingValues: MessageContentPadding = MessageContentPadding.None
    ){
        groups.add(
            MessageContentGroup(listOf(MessageContent(contentData, paddingValues)), showBubble)
        )
    }

    // ---- Quote ----
// todo CONVOv3: sort out properly
    private fun mapQuote(record: MessageRecord): QuoteMessageData? {
        val quote = (record as? MmsMessageRecord)?.quote ?: return null

        val raw = quote.text?.ifBlank { null }

        val subtitle: AnnotatedString = if (raw != null) {
            val parsed = MentionUtilities.parseAndSubstituteMentions(
                recipientRepository = recipientRepository,
                input = raw,
                context = context
            )

            MessageTextFormatter.formatMessage(
                parsed = parsed,
                isOutgoing = record.isOutgoing
            )
        } else {
            AnnotatedString(context.getString(R.string.document))
        }

        val icon: MessageQuoteIcon = MessageQuoteIcon.Bar
        /*when {
            quote.attachment.thumbnail != null -> MessageQuoteIcon.Image(
                uri = quote.attachment.thumbnail!!.uri?.toUri() ?: "".toUri(),
                filename = quote.attachment.fileName ?: "",
            )

            else -> MessageQuoteIcon.Bar
        }*/

        // title
        val title = if(quote.author.isLocalNumber) context.getString(R.string.you)
        else quote.author.displayName()

        return QuoteMessageData(
            title = title,
            subtitle = subtitle,
            icon = icon,
            showProBadge = quote.author.shouldShowProBadge,
            quotedMessageId = quote.quoteMessageId
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