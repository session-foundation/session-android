package org.thoughtcrime.securesms.conversation.v3.compose.message

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.core.net.toUri
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement
import kotlinx.coroutines.launch

//todo CONVOv3 status animated icon for disappearing messages
//todo CONVOv3 text formatting in bubble including mentions and links
//todo CONVOv3 typing indicator
//todo CONVOv3 long press views (overlay+message+recent reactions+menu)
//todo CONVOv3 control messages
//todo CONVOv3 bottom search
//todo CONVOv3 text input
//todo CONVOv3 voice recording
//todo CONVOv3 collapsible + menu for attachments
//todo CONVOv3 attachment controls
//todo CONVOv3 deleted messages
//todo CONVOv3 swipe to reply
//todo CONVOv3 inputbar quote/reply
//todo CONVOv3 proper accessibility on overall message control
//todo CONVOv3 verify immutability/stability of data classes

/**
 * The overall Message composable
 * This controls the width and position of the message as a whole
 */
@Composable
fun Message(
    data: MessageViewData,
    modifier: Modifier = Modifier,
    highlight: HighlightMessage? = null,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit = {},
    onExpandText: suspend (Int) -> Unit = {},
) {
    val highlightAlpha = rememberHighlightAlpha(
        messageId = data.id,
        trigger = highlight,
    )
    val scope = rememberCoroutineScope()

    // Keeping some state in the composable to avoid rebuilding the conversation list each time
    // small UI state changes locally in a message, like this expanded state
    var expandedText by rememberSaveable(data.id.serialize()) { mutableStateOf(false) }

    when (data.layout) {
        MessageLayout.CONTROL -> {
            ControlMessage(data = data, modifier = modifier)
        }
        MessageLayout.INCOMING, MessageLayout.OUTGOING -> {
            RecipientMessage(
                data = data,
                modifier = modifier,
                expandedText = expandedText,
                sendCommand = sendCommand,
                onExpandText = { extraHeightPx ->
                    if (!expandedText) {
                        scope.launch {
                            onExpandText(extraHeightPx)
                            expandedText = true
                        }
                    }
                },
                highlightAlpha = highlightAlpha
            )
        }
    }
}

@Composable
fun RecipientMessage(
    data: MessageViewData,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit,
    modifier: Modifier = Modifier,
    expandedText: Boolean = false,
    onExpandText: (Int) -> Unit = {},
    highlightAlpha: Float = 0f,
){
    val outgoing = data.layout == MessageLayout.OUTGOING

    val bottomPadding = when (data.clusterPosition) {
        ClusterPosition.BOTTOM, ClusterPosition.ISOLATED -> LocalDimensions.current.smallSpacing // vertical space between mesasges of different authors
        ClusterPosition.TOP, ClusterPosition.MIDDLE -> LocalDimensions.current.xxxsSpacing // vertical space between cluster of messages from same author
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .padding(bottom = bottomPadding)
    ) {
        val maxMessageWidth = min(
            LocalDimensions.current.maxMessageWidth, // cap a max width for large content like tablets and large landscape devices
            max(
            LocalDimensions.current.minMessageWidth,
            this.maxWidth * 0.8f // 80% of available width
        ))

        RecipientMessageContent(
            modifier = Modifier
                .align(if (outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = maxMessageWidth)
                .wrapContentWidth(),
            data = data,
            maxWidth = maxMessageWidth,
            expandedText = expandedText,
            sendCommand = sendCommand,
            onExpandText = onExpandText,
            highlightAlpha = highlightAlpha
        )
    }
}

/**
 * All the content of a message: Bubble with its internal content, avatar, status
 */
@Composable
fun RecipientMessageContent(
    data: MessageViewData,
    maxWidth: Dp,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit,
    modifier: Modifier = Modifier,
    expandedText: Boolean = false,
    onExpandText: (Int) -> Unit = {},
    highlightAlpha: Float = 0f,
) {
    val outgoing = data.layout == MessageLayout.OUTGOING

    Column(
        modifier = modifier,
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
    ) {
        Row {
            if (data.avatar !is MessageAvatar.None) {
                if(data.avatar is MessageAvatar.Visible) {
                    Avatar(
                        modifier = Modifier.align(Alignment.Bottom),
                        size = LocalDimensions.current.iconMediumAvatar,
                        data = data.avatar.data
                    )
                } else {
                    Box(
                        modifier = Modifier.size(LocalDimensions.current.iconMediumAvatar)
                            .clearAndSetSemantics {} // no ax for this empty box
                    )
                }

                Spacer(modifier = Modifier.width(LocalDimensions.current.xsSpacing))
            }

            Column(
                horizontalAlignment = if(outgoing) Alignment.End else Alignment.Start
            )
            {
                if (data.showDisplayName) {
                    Row {
                        ProBadgeText(
                            modifier = Modifier.weight(1f, fill = false),
                            text = data.displayName,
                            textStyle = LocalType.current.base.bold()
                                .copy(color = LocalColors.current.text),
                            showBadge = data.showProBadge,
                        )

                        if (!data.displayNameExtra.isNullOrEmpty()) {
                            Spacer(Modifier.width(LocalDimensions.current.xxxsSpacing))

                            Text(
                                text = "(${data.displayNameExtra})",
                                maxLines = 1,
                                style = LocalType.current.base
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
                }

                data.contentGroups.forEachIndexed { index, group ->
                    if (index > 0) Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

                    val contentColumn = @Composable {
                        Column {
                            group.contents.forEach { content ->
                                MessageContentRenderer(
                                    content = content,
                                    layout = data.layout,
                                    maxWidth = maxWidth,
                                    expandedText = expandedText,
                                    sendCommand = sendCommand,
                                    onExpandText = onExpandText,
                                    highlightAlpha = highlightAlpha
                                )
                            }
                        }
                    }

                    if (group.showBubble) {
                        MessageBubble(
                            modifier = Modifier.accentHighlight(alpha = highlightAlpha),
                            color = if (outgoing) LocalColors.current.accent else LocalColors.current.backgroundBubbleReceived,
                            content = contentColumn
                        )
                    } else {
                        contentColumn() // Render naked content (e.g., for media)
                    }
                }
            }
        }

        //////// Below the Avatar + Message bubbles ////

        val indentation = if(outgoing) 0.dp
        else if (data.avatar !is MessageAvatar.None) LocalDimensions.current.iconMediumAvatar + LocalDimensions.current.smallSpacing
        else 0.dp

        // reactions
        if (data.reactions != null) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
            EmojiReactions(
                modifier = Modifier.padding(start = indentation),
                reactions = data.reactions.reactions,
                isExpanded = data.reactions.isExtended,
                outgoing = outgoing,
                onReactionClick = {
                    //todo CONVOv3 implement
                },
                onReactionExpandClick = {
                    //todo CONVOv3 implement
                },
                onReactionShowLessClick = {
                    //todo CONVOv3 implement
                },
                onReactionLongClick = {
                    //todo CONVOv3 implement
                }
            )
        }

        // status
        if (data.status != null) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
            MessageStatus(
                modifier = Modifier
                    .padding(horizontal = LocalDimensions.current.tinySpacing)
                    .padding(start = indentation)
                    .align(if (outgoing) Alignment.End else Alignment.Start),
                data = data.status
            )
        }
    }
}

@Composable
fun MessageContentRenderer(
    content: MessageContent,
    layout: MessageLayout,
    maxWidth: Dp,
    expandedText: Boolean,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit,
    onExpandText: (Int) -> Unit,
    highlightAlpha: Float = 0f,
) {
    val isOutgoing = layout == MessageLayout.OUTGOING
    Box(
        modifier = Modifier.padding(
            when(content.extraPadding){
                MessageContentPadding.Bottom -> PaddingValues(bottom = defaultMessageBubblePadding().calculateBottomPadding())
                else -> PaddingValues()
            }
        )
    ) {
        when (content.contentData) {
            is MessageContentData.Text -> ExpandableMessageText(
                text = content.contentData.text,
                isOutgoing = isOutgoing,
                isExpanded = expandedText,
                modifier = Modifier.padding(defaultMessageBubblePadding()),
                onUrlClick = {
                    sendCommand(ConversationV3ViewModel.Commands.ShowOpenUrlDialog(it))
                },
                onExpand = onExpandText
            )

            is MessageContentData.Quote -> MessageQuote(
                quote = content.contentData.data,
                outgoing = isOutgoing,
                onQuoteTapped = { messageId ->
                    sendCommand(
                        ConversationV3ViewModel.Commands.ScrollToMessage(messageId)
                    )
                }
            )

            is MessageContentData.Link ->
                MessageLink(
                    data = content.contentData.data,
                    outgoing = isOutgoing,
                    sendCommand = sendCommand
                )

            is MessageContentData.Document ->
                DocumentMessage(
                    data = content.contentData.data,
                    outgoing = isOutgoing
                )

            is MessageContentData.Audio ->
                AudioMessage(
                    data = content.contentData.data,
                    outgoing = isOutgoing
                )

            is MessageContentData.CommunityInvite ->
                CommunityInviteMessage(
                    name = content.contentData.name,
                    url = content.contentData.url,
                    outgoing = isOutgoing
                )

            is MessageContentData.Media ->
                MediaMessage(
                    modifier = Modifier.accentHighlight(alpha = highlightAlpha),
                    items = content.contentData.items,
                    loading = content.contentData.loading,
                    maxWidth = maxWidth
                )
        }
    }
}

/**
 * Basic message building block: Bubble
 */
@Composable
fun MessageBubble(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier
            .background(
                color = color,
                shape = RoundedCornerShape(LocalDimensions.current.messageCornerRadius)
            )
            .clip(shape = RoundedCornerShape(LocalDimensions.current.messageCornerRadius))
    ) {
        content()
    }
}

@Composable
fun MessageStatus(
    data: MessageViewStatus,
    modifier: Modifier = Modifier
){
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.tinySpacing)
    ) {
        Text(
            text = data.name,
            style = LocalType.current.small,
            color = LocalColors.current.textSecondary
        )

        when(data.icon){
            is MessageViewStatusIcon.DrawableIcon -> {
                Image(
                    painter = painterResource(id = data.icon.icon),
                    colorFilter = ColorFilter.tint(LocalColors.current.textSecondary),
                    contentDescription = null,
                    modifier = Modifier.size(LocalDimensions.current.iconStatus)
                )
            }
            is MessageViewStatusIcon.DisappearingMessageIcon -> {
                //todo Convov3 disappearing message icon
            }
        }
    }
}

@Composable
internal fun getTextColor(outgoing: Boolean) = if(outgoing) LocalColors.current.textBubbleSent
else LocalColors.current.textBubbleReceived

@Composable
internal fun defaultMessageBubblePadding() = PaddingValues(
    horizontal = LocalDimensions.current.smallSpacing,
    vertical = LocalDimensions.current.messageVerticalPadding
)

data class MessageViewData(
    val id: MessageId,
    val layout: MessageLayout,
    val contentGroups: List<MessageContentGroup>,
    val displayName: String,
    val displayNameExtra: String? = null,  // when you want to add extra text to the display name, like the blinded id - after the pro badge)
    val showDisplayName: Boolean = false,
    val showProBadge: Boolean = false,
    val avatar: MessageAvatar = MessageAvatar.None,
    val status: MessageViewStatus? = null,
    val reactions: ReactionViewState? = null,
    val clusterPosition: ClusterPosition = ClusterPosition.ISOLATED
)

data class MessageContentGroup(
    val contents: List<MessageContent>,
    val showBubble: Boolean = true //whether the grouped content should be placed in a bubble
)

data class MessageContent(
    val contentData: MessageContentData,
    val extraPadding: MessageContentPadding = MessageContentPadding.None
)

sealed interface MessageContentPadding{
    data object None: MessageContentPadding
    data object Bottom: MessageContentPadding
}

sealed interface MessageContentData {
    data class Text(val text: AnnotatedString) : MessageContentData
    data class Media(val items: List<MessageMediaItem>, val loading: Boolean) : MessageContentData
    data class Link(val data: MessageLinkData) : MessageContentData
    data class Quote(val data: QuoteMessageData) : MessageContentData
    data class Document(val data: DocumentMessageData) : MessageContentData
    data class Audio(val data: AudioMessageData) : MessageContentData
    data class CommunityInvite(val name: String, val url: String) : MessageContentData
}

enum class MessageLayout {
    INCOMING,
    OUTGOING,
    CONTROL
}

@Stable
data class HighlightMessage(val token: Long)
enum class ClusterPosition {
    TOP,
    MIDDLE,
    BOTTOM,
    ISOLATED
}

sealed interface MessageAvatar {
    data object None: MessageAvatar
    data object Invisible: MessageAvatar// the avatar is not visible but still takes up the space
    data class Visible(val data: AvatarUIData): MessageAvatar
}

data class ReactionViewState(
    val reactions: List<ReactionItem>,
    val isExtended: Boolean,
)

data class ReactionItem(
    val emoji: String,
    val count: Int,
    val selected: Boolean
)

sealed class MessageQuoteIcon {
    data object Bar: MessageQuoteIcon()
    data class Icon(@DrawableRes val icon: Int): MessageQuoteIcon()
    data class Image(
        val uri: Uri,
        val filename: String
    ): MessageQuoteIcon()
}

data class MessageViewStatus(
    val name: String,
    val icon: MessageViewStatusIcon
)

sealed interface MessageViewStatusIcon{
    data class DrawableIcon(@DrawableRes val icon: Int): MessageViewStatusIcon
    data object DisappearingMessageIcon: MessageViewStatusIcon
}

/*@PreviewScreenSizes*/
@Preview
@Composable
fun MessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxSize().padding(LocalDimensions.current.spacing)

        ) {
            var testData: HighlightMessage? by remember { mutableStateOf(null) }

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                showProBadge = true,
                displayNameExtra = "(some extra text)",
                layout = MessageLayout.OUTGOING,
                contentGroups = PreviewMessageData.textGroup(),
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                    testData = HighlightMessage(System.currentTimeMillis())
                }),
                highlight = testData,
                data = MessageViewData(
                    id = MessageId(0, false),
                    displayName = "Toto",
                    showDisplayName = true,
                    avatar = PreviewMessageData.sampleAvatar,
                    layout = MessageLayout.INCOMING,
                    contentGroups = PreviewMessageData.textGroup(
                        text = "Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"
                    ),
                )
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = PreviewMessageData.textGroup(
                    text = "Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"
                ),
                status = PreviewMessageData.sentStatus
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                layout = MessageLayout.INCOMING,
                contentGroups = PreviewMessageData.textGroup(),
                status = PreviewMessageData.sentStatus
            ))
        }
    }
}

@Preview
@Composable
fun MessageReactionsPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxSize().padding(LocalDimensions.current.spacing)

        ) {
            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = PreviewMessageData.textGroup(
                    text = "I have 3 emoji reactions"
                ),
                reactions = ReactionViewState(
                    reactions = listOf(
                        ReactionItem("👍", 3, selected = true),
                        ReactionItem("❤️", 12, selected = false),
                        ReactionItem("😂", 1, selected = false),
                    ),
                    isExtended = false,
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                layout = MessageLayout.INCOMING,
                contentGroups = PreviewMessageData.textGroup(
                    text = "I have lots of reactions - Closed"
                ),
                reactions = ReactionViewState(
                    reactions = listOf(
                        ReactionItem("👍", 3, selected = true),
                        ReactionItem("❤️", 12, selected = false),
                        ReactionItem("😂", 1, selected = false),
                        ReactionItem("😮", 5, selected = false),
                        ReactionItem("😢", 2, selected = false),
                        ReactionItem("🔥", 8, selected = false),
                        ReactionItem("💕", 8, selected = false),
                        ReactionItem("🐙", 8, selected = false),
                        ReactionItem("✅", 8, selected = false),
                    ),
                    isExtended = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                layout = MessageLayout.INCOMING,
                contentGroups = PreviewMessageData.textGroup(
                    text = "I have lots of reactions - Open"
                ),
                reactions = ReactionViewState(
                    reactions = listOf(
                        ReactionItem("👍", 3, selected = true),
                        ReactionItem("❤️", 12, selected = false),
                        ReactionItem("😂", 1, selected = false),
                        ReactionItem("😮", 5, selected = false),
                        ReactionItem("😢", 2, selected = false),
                        ReactionItem("🔥", 8, selected = false),
                        ReactionItem("💕", 8, selected = false),
                        ReactionItem("🐙", 8, selected = false),
                        ReactionItem("✅", 8, selected = false),
                    ),
                    isExtended = true,
                )
            ))
        }
    }
}

@Preview
@Composable
fun DocumentMessagePreviewReuse(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    DocumentMessagePreview(colors)
}

@Preview
@Composable
fun QuoteMessagePreviewReuse(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    QuoteMessagePreview(colors)
}

@Preview
@Composable
fun LinkMessagePreviewReuse(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    LinkMessagePreview(colors)
}

@Preview
@Composable
fun AudioMessagePreviewReuse(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    AudioMessagePreview(colors)
}

@Preview
@Composable
fun MediaMessagePreviewReuse(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    MediaMessagePreview(colors)
}

object PreviewMessageData {

    // Common data
    val sampleAvatar = MessageAvatar.Visible(
        AvatarUIData(listOf(AvatarUIElement(name = "TO", color = primaryBlue)))
    )
    val sentStatus = MessageViewStatus(
        name = "Sent",
        icon = MessageViewStatusIcon.DrawableIcon(icon = R.drawable.ic_circle_check)
    )

    fun textGroup(text: String = "Hi there") = listOf(
        MessageContentGroup(listOf(MessageContent(text(text))), showBubble = true)
    )

    fun textGroup(text: AnnotatedString) = listOf(
        MessageContentGroup(listOf(MessageContent(text(text))), showBubble = true)
    )

    fun audioGroup(
        title: String = "Voice Message",
        playing: Boolean = true
    ) = listOf(
        MessageContentGroup(listOf(MessageContent(MessageContentData.Audio(AudioMessageData(
            title = title, speedText = "1x", remainingText = "0:20",
            durationMs = 83_000L, positionMs = 23_000L, isPlaying = playing, showLoader = false
        )))), showBubble = true)
    )

    fun documentGroup(
        name: String = "Document.pdf",
        loading: Boolean = false
    ) = listOf(
        MessageContentGroup(listOf(MessageContent(document(name, loading))), showBubble = true)
    )

    fun mediaGroup(
        items: List<MessageMediaItem>,
        text: String? = null
    ) = buildList {
        if(text != null) add(MessageContentGroup(
            listOf(MessageContent(MessageContentData.Text(AnnotatedString(text)))), showBubble = true))
        add(mediaGroup(items))
    }

    fun mediaGroup(
        items: List<MessageMediaItem>,
    ) = MessageContentGroup(listOf(MessageContent(MessageContentData.Media(items, false))), showBubble = false)

    fun quoteGroup(
        icon: MessageQuoteIcon = MessageQuoteIcon.Bar,
        title: String = "Toto",
        subtitle: String = "This is a quote",
        text: String? = null,
        showProBadge: Boolean = false
    ): List<MessageContentGroup> {
        val group = mutableListOf<MessageContentData>()
        group.add(
            quote(title = title, subtitle = subtitle, icon = icon, showProBadge = showProBadge)
        )

        if(text != null) group.add(MessageContentData.Text(AnnotatedString(text)))

        return listOf(MessageContentGroup(group.map { MessageContent(it) }, showBubble = true))
    }

    // Individual item helpers
    fun text(
        text: String = "Hi there",
    ) = MessageContentData.Text(AnnotatedString(text))
    fun text(
        text: AnnotatedString,
    ) = MessageContentData.Text(text)

    fun document(
        name: String = "Document.pdf",
        loading: Boolean = false
    ) = MessageContentData.Document(DocumentMessageData(
        name = name, size = "5.4MB", uri = "", loading = loading
    ))
    fun image(width: Int = 100, height: Int = 100, loading: Boolean = false) = MessageMediaItem.Image("".toUri(), "img.jpg", loading, width, height)
    fun video(width: Int = 100, height: Int = 100, loading: Boolean = false) = MessageMediaItem.Video("".toUri(), "vid.mp4", loading, width, height)
    fun quote(title: String = "Toto", subtitle: String = "This is a quote", icon: MessageQuoteIcon = MessageQuoteIcon.Bar, showProBadge: Boolean = false) =
        MessageContentData.Quote(QuoteMessageData(title, AnnotatedString(subtitle), icon, showProBadge, MessageId(0, false)))

    fun composeContent(vararg content: MessageContentData): MessageContentGroup {
        return MessageContentGroup(
            contents = content.map { MessageContent(it) },
        )
    }
}
