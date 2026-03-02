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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import network.loki.messenger.R
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

//todo CONVOv3 status animated icon for disappearing messages
//todo CONVOv3 text formatting in bubble including mentions and links
//todo CONVOv3 typing indicator
//todo CONVOv3 long press views (overlay+message+recent reactions+menu)
//todo CONVOv3 control messages
//todo CONVOv3 bottom search
//todo CONVOv3 text input
//todo CONVOv3 voice recording
//todo CONVOv3 collapsible + menu for attachments
//todo CONVOv3 jump down to last message button
//todo CONVOv3 attachment controls
//todo CONVOv3 deleted messages
//todo CONVOv3 swipe to reply
//todo CONVOv3 inputbar quote/reply
//todo CONVOv3 proper accessibility on overall message control
//todo CONVOv3 new "read more" expandable feature

/**
 * The overall Message composable
 * This controls the width and position of the message as a whole
 */
@Composable
fun Message(
    data: MessageViewData,
    modifier: Modifier = Modifier
) {
    when(data.type){
        is MessageType.RecipientMessage -> {
            RecipientMessage(
                data = data,
                type = data.type,
                modifier = modifier
            )
        }

        is MessageType.ControlMessage -> {
           /* ControlMessage(
                data = data,
                modifier = modifier
            )*/
        }
    }
}

@Composable
fun RecipientMessage(
    data: MessageViewData,
    type: MessageType.RecipientMessage,
    modifier: Modifier = Modifier
){
    val bottomPadding = when (data.clusterPosition) {
        ClusterPosition.BOTTOM, ClusterPosition.ISOLATED -> LocalDimensions.current.smallSpacing // vertical space between mesasges of different authors
        ClusterPosition.TOP, ClusterPosition.MIDDLE -> LocalDimensions.current.xxxsSpacing // vertical space between cluster of messages from same author
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .padding(bottom = bottomPadding)
    ) {
        val maxMessageWidth = max(
            LocalDimensions.current.minMessageWidth,
            this.maxWidth * 0.8f // 80% of available width
            //todo ConvoV3 we probably should cap the max so that large screens/tablets don't extend too far
        )

        RecipientMessageContent(
            modifier = Modifier
                .align(if (type.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = maxMessageWidth)
                .wrapContentWidth(),
            data = data,
            type = type,
            maxWidth = maxMessageWidth
        )
    }
}

/**
 * All the content of a message: Bubble with its internal content, avatar, status
 */
@Composable
fun RecipientMessageContent(
    data: MessageViewData,
    type: MessageType.RecipientMessage,
    modifier: Modifier = Modifier,
    maxWidth: Dp
) {

    Column(
        modifier = modifier,
        horizontalAlignment = if (type.outgoing) Alignment.End else Alignment.Start
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
                horizontalAlignment = if(type.outgoing) Alignment.End else Alignment.Start
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

                // There can be two bubbles in a message: First one contains quotes, links and message text
                // The second one contains audio, document, images and video
                val hasFirstBubble =
                    data.quote != null || data.link != null || type.text != null
                            || type is MessageType.RecipientMessage.CommunityInvite
                val hasSecondBubble = data.type !is MessageType.RecipientMessage.Text
                        && type !is MessageType.RecipientMessage.CommunityInvite

                // First bubble
                if (hasFirstBubble) {
                    MessageBubble(
                        modifier = Modifier.accentHighlight(data.highlightKey),
                        color = if (type.outgoing) LocalColors.current.accent
                        else LocalColors.current.backgroundBubbleReceived
                    ) {
                        // community invites
                        if (data.type is MessageType.RecipientMessage.CommunityInvite) {
                            //todo convov3 add onclick for community invite
                            CommunityInviteMessage(
                                data = data,
                                type = data.type,
                                modifier = Modifier.accentHighlight(data.highlightKey),
                            )
                        } else { // regular recipient messages
                            Column {
                                // Display quote if there is one
                                if (data.quote != null) {
                                    MessageQuote(
                                        modifier = Modifier.padding(
                                            bottom =
                                                if (data.link == null && type.text == null)
                                                    defaultMessageBubblePadding().calculateBottomPadding()
                                                else 0.dp
                                        ),
                                        outgoing = type.outgoing,
                                        quote = data.quote
                                    )
                                }

                                // display link data if any
                                if (data.link != null) {
                                    MessageLink(
                                        modifier = Modifier.padding(top = if (data.quote != null) LocalDimensions.current.xxsSpacing else 0.dp),
                                        data = data.link,
                                        outgoing = type.outgoing
                                    )
                                }

                                if (type.text != null) {
                                    // Text messages
                                    MessageText(
                                        modifier = Modifier.padding(defaultMessageBubblePadding()),
                                        text = type.text!!,
                                        outgoing = type.outgoing
                                    )
                                }
                            }
                        }
                    }
                }

                // Second bubble
                if (hasSecondBubble) {
                    // add spacing if there is a first bubble
                    if (hasFirstBubble) {
                        Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
                    }

                    // images and videos are a special case and aren't actually surrounded in a visible bubble
                    if (data.type is MessageType.RecipientMessage.Media) {
                        MediaMessage(
                            modifier = Modifier.accentHighlight(data.highlightKey),
                            data = data.type,
                            maxWidth = maxWidth
                        )
                    } else {
                        MessageBubble(
                            modifier = Modifier.accentHighlight(data.highlightKey),
                            color = if (type.outgoing) LocalColors.current.accent
                            else LocalColors.current.backgroundBubbleReceived
                        ) {
                            // Apply content based on message type
                            when (data.type) {
                                // Document messages
                                is Document -> DocumentMessage(
                                    data = data.type
                                )

                                // Audio messages
                                is Audio -> AudioMessage(
                                    data = data.type
                                )

                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        //////// Below the Avatar + Message bubbles ////

        val indentation = if(type.outgoing) 0.dp
        else if (data.avatar !is MessageAvatar.None) LocalDimensions.current.iconMediumAvatar + LocalDimensions.current.smallSpacing
        else 0.dp

        // reactions
        if (data.reactionsState != null) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
            EmojiReactions(
                modifier = Modifier.padding(start = indentation),
                reactions = data.reactionsState.reactions,
                isExpanded = data.reactionsState.isExtended,
                outgoing = type.outgoing,
                onReactionClick = {
                    //todo CONVOv3 implement
                },
                onExpandClick = {
                    //todo CONVOv3 implement
                },
                onShowLessClick = {
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
                    .align(if (type.outgoing) Alignment.End else Alignment.Start),
                data = data.status
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
fun MessageText(
    text: AnnotatedString,
    outgoing: Boolean,
    modifier: Modifier = Modifier
){
    Text(
        modifier = modifier,
        text = text,
        style = LocalType.current.large,
        color = getTextColor(outgoing),
    )
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
    val type: MessageType,
    val displayName: String,
    val displayNameExtra: String? = null, // when you want to add extra text to the display name, like the blinded id - after the pro badge)
    val showDisplayName: Boolean = false,
    val showProBadge: Boolean = false,
    val avatar: MessageAvatar = MessageAvatar.None,
    val status: MessageViewStatus? = null,
    val quote: MessageQuote? = null,
    val link: MessageLinkData? = null,
    val reactionsState: ReactionViewState? = null,
    val highlightKey: Any? = null,
    val clusterPosition: ClusterPosition = ClusterPosition.ISOLATED
)

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
    val onReactionClick: (String) -> Unit,
    val onReactionLongClick: (String) -> Unit,
    val onShowMoreClick: () -> Unit
)

data class ReactionItem(
    val emoji: String,
    val count: Int,
    val selected: Boolean
)

data class MessageQuote(
    val title: String,
    val subtitle: String,
    val icon: MessageQuoteIcon
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

sealed interface MessageType{

    sealed interface RecipientMessage: MessageType {
        val outgoing: Boolean
        val text: AnnotatedString?

        data class Text(
            override val outgoing: Boolean,
            override val text: AnnotatedString
        ) : RecipientMessage

        data class Media(
            override val outgoing: Boolean,
            val items: List<MessageMediaItem>,
            val loading: Boolean,
            override val text: AnnotatedString? = null
        ) : RecipientMessage

        data class CommunityInvite(
            override val outgoing: Boolean,
            override val text: AnnotatedString = AnnotatedString(""),
            val communityName: String,
            val url: String
        ) : RecipientMessage
    }

    sealed interface ControlMessage: MessageType {

    }
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
            var testData by remember {
                mutableStateOf(
                    MessageViewData(
                        id = MessageId(0, false),
                        displayName = "Toto",
                        showProBadge = true,
                        displayNameExtra = "(some extra text)",
                        type = PreviewMessageData.text()
                    )
                )
            }

            var testData2 by remember {
                mutableStateOf(
                    MessageViewData(
                        id = MessageId(0, false),
                        displayName = "Toto",
                        showDisplayName = true,
                        avatar = PreviewMessageData.sampleAvatar,
                        type = PreviewMessageData.text(
                            outgoing = false,
                            text = "Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"
                        )
                    )
                )
            }

            LaunchedEffect(Unit) {
                delay(3000)

                // to test out the selection
                testData = testData.copy(highlightKey = System.currentTimeMillis())
                testData2 = testData2.copy(highlightKey = System.currentTimeMillis())
            }

            Message(data = testData)

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                    testData2 = testData2.copy(highlightKey = System.currentTimeMillis())
                }),
                data = testData2
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = PreviewMessageData.text(
                    text = "Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"
                ),
                status = PreviewMessageData.sentStatus
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(
                    outgoing = false,
                    text = "Hello"
                ),
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
                type = PreviewMessageData.text(
                    text = "I have 3 emoji reactions"
                ),
                reactionsState = ReactionViewState(
                    reactions = listOf(
                        ReactionItem("👍", 3, selected = true),
                        ReactionItem("❤️", 12, selected = false),
                        ReactionItem("😂", 1, selected = false),
                    ),
                    isExtended = false,
                    onReactionClick = {},
                    onReactionLongClick = {},
                    onShowMoreClick = {}
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(
                    outgoing = false,
                    text = "I have lots of reactions - Closed"
                ),
                reactionsState = ReactionViewState(
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
                    isExtended = false,
                    onReactionClick = {},
                    onReactionLongClick = {},
                    onShowMoreClick = {}
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(
                    outgoing = false,
                    text = "I have lots of reactions - Open"
                ),
                reactionsState = ReactionViewState(
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
                    onReactionClick = {},
                    onReactionLongClick = {},
                    onShowMoreClick = {}
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

    fun communityInvite(
        outgoing: Boolean = true
    ) = MessageType.RecipientMessage.CommunityInvite(
        outgoing = outgoing,
        text = AnnotatedString(""),
        communityName = "Test Community",
        url = "https://www.test-community-url.com/testing-the-url-look-and-feel",
    )

    fun text(
        text: String = "Hi there",
        outgoing: Boolean = true
    ) = MessageType.RecipientMessage.Text(outgoing = outgoing, AnnotatedString(text))

    fun document(
        name: String = "Document name",
        size: String = "5.4MB",
        outgoing: Boolean = true,
        loading: Boolean = false
    ) = Document(
        outgoing = outgoing,
        name = name,
        size = size,
        loading = loading,
        uri = ""
    )

    fun audio(
        outgoing: Boolean = true,
        title: String = "Voice Message",
        speedText: String = "1x",
        remainingText: String = "0:20",
        durationMs: Long = 83_000L,
        positionMs: Long = 23_000L,
        bufferedPositionMs: Long = 35_000L,
        playing: Boolean = true,
        showLoader: Boolean = false
    ) = Audio(
        outgoing = outgoing,
        title = title,
        speedText = speedText,
        remainingText = remainingText,
        durationMs = durationMs,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        isPlaying = playing,
        showLoader = showLoader,
    )

    fun image(
        loading: Boolean = false,
        width: Int = 100,
        height: Int = 100,
    ) = MessageMediaItem.Image(
        "".toUri(),
        "",
        loading = loading,
        width = width,
        height = height
    )

    fun video(
        loading: Boolean = false,
        width: Int = 100,
        height: Int = 100,
    ) = MessageMediaItem.Video(
        "".toUri(),
        "",
        loading = loading,
        width = width,
        height = height
    )

    fun quote(
        title: String = "Toto",
        subtitle: String = "This is a quote",
        icon: MessageQuoteIcon = MessageQuoteIcon.Bar
    ) = MessageQuote(
        title = title,
        subtitle = subtitle,
        icon = icon
    )

    fun quoteImage(
        uri: Uri = "".toUri(),
        filename: String = ""
    ) = MessageQuoteIcon.Image(
        uri = uri,
        filename = filename
    )
}



