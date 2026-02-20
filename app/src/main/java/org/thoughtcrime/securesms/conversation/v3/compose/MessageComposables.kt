package org.thoughtcrime.securesms.conversation.v3.compose

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.blackAlpha06
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement

//todo CONVOv3 status animated icon for disappearing messages
//todo CONVOv3 highlight effect (needs to work on all types and shapes (how should it work for combos like message + image? overall effect?)
//todo CONVOv3 text formatting in bubble including mentions and links
//todo CONVOv3 typing indicator
//todo CONVOv3 long press views (overlay+message+recent reactions+menu)
//todo CONVOv3 reactions
//todo CONVOv3 control messages
//todo CONVOv3 time/date "separator"
//todo CONVOv3 bottom search
//todo CONVOv3 text input
//todo CONVOv3 voice recording
//todo CONVOv3 collapsible + menu for attachments
//todo CONVOv3 jump down to last message button
//todo CONVOv3 community invites
//todo CONVOv3 attachment controls
//todo CONVOv3 deleted messages
//todo CONVOv3 swipe to reply
//todo CONVOv3 inputbar quote/reply
//todo CONVOv3 proper accessibility on overall message control
//todo CONVOv3 new "read more" expandable feature
//todo CONVOv3 new audio player

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

/**
 * All the content of a message: Bubble with its internal content, avatar, status
 */
@Composable
fun MessageContent(
    data: MessageViewData,
    modifier: Modifier = Modifier,
    maxWidth: Dp
) {
    Column(
        modifier = modifier,
    ) {
        Row {
            if (data.avatar != null) {
                Avatar(
                    modifier = Modifier.align(Alignment.Bottom),
                    size = LocalDimensions.current.iconMediumAvatar,
                    data = data.avatar
                )

                Spacer(modifier = Modifier.width(LocalDimensions.current.smallSpacing))
            }

            Column(
                horizontalAlignment = if(data.type.outgoing) Alignment.End else Alignment.Start
            ) {
                if (data.displayName) {
                    Text(
                        modifier = Modifier.padding(start = LocalDimensions.current.smallSpacing),
                        text = data.author,
                        style = LocalType.current.base.bold(),
                        color = LocalColors.current.text
                    )

                    Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
                }

                // There can be two bubbles in a message: First one contains quotes, links and message text
                // The second one contains audio, document, images and video
                val hasFirstBubble = data.quote != null || data.link != null || data.type.text != null
                val hasSecondBubble = data.type !is MessageType.Text

                // First bubble
                if (hasFirstBubble) {
                    MessageBubble(
                        color = if (data.type.outgoing) LocalColors.current.accent
                        else LocalColors.current.backgroundBubbleReceived
                    ) {
                        Column {
                            // Display quote if there is one
                            if (data.quote != null) {
                                MessageQuote(
                                    modifier = Modifier.padding(bottom =
                                        if (data.link == null && data.type.text == null)
                                            defaultMessageBubblePadding().calculateBottomPadding()
                                        else 0.dp
                                    ),
                                    outgoing = data.type.outgoing,
                                    quote = data.quote
                                )
                            }

                            // display link data if any
                            if (data.link != null) {
                                MessageLink(
                                    modifier = Modifier.padding(top = if (data.quote != null) LocalDimensions.current.xxsSpacing else 0.dp),
                                    data = data.link,
                                    outgoing = data.type.outgoing
                                )
                            }

                            if(data.type.text != null){
                                // Text messages
                                MessageText(
                                    modifier = Modifier.padding(defaultMessageBubblePadding()),
                                    text = data.type.text!!,
                                    outgoing = data.type.outgoing
                                )
                            }
                        }
                    }
                }

                // Second bubble
                if(hasSecondBubble){
                    // add spacing if there is a first bubble
                    if(hasFirstBubble){
                        Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
                    }

                    // images and videos are a special case and aren' actually surrounded in a visible bubble
                    if(data.type is MessageType.Media){
                        MediaMessage(
                            data = data.type,
                            maxWidth = maxWidth
                        )
                    } else {
                        MessageBubble(
                            color = if (data.type.outgoing) LocalColors.current.accent
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

        // status
        if (data.status != null) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))
            MessageStatus(
                modifier = Modifier.align(Alignment.End)
                    .padding(horizontal = 2.dp),
                data = data.status
            )
        }
    }
}

/**
 * The overall Message composable
 * This controls the width and position of the message as a whole
 */
@Composable
fun Message(
    data: MessageViewData,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val maxMessageWidth = max(
            LocalDimensions.current.minMessageWidth,
            this.maxWidth * 0.8f // 80% of available width
        )

        MessageContent(
            modifier = Modifier
                .align(if (data.type.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = maxMessageWidth)
                .wrapContentWidth(),
            data = data,
            maxWidth = maxMessageWidth
        )
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
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = data.name,
            style = LocalType.current.small,
            color = LocalColors.current.text
        )

        when(data.icon){
            is MessageViewStatusIcon.DrawableIcon -> {
                Image(
                    painter = painterResource(id = data.icon.icon),
                    colorFilter = ColorFilter.tint(LocalColors.current.text),
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
fun MessageLink(
    data: MessageLinkData,
    outgoing: Boolean,
    modifier: Modifier = Modifier
){
    Row(
        modifier = modifier.fillMaxWidth().background(
            color = blackAlpha06
        ),
    ) {
        Box(
            modifier = Modifier.size(100.dp)
                .background(color = blackAlpha06)
        ){
            if(data.imageUri == null){
                Image(
                    painter = painterResource(id = R.drawable.ic_link),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LocalColors.current.text),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .crossfade(true)
                        .data(data.imageUri)
                        .build(),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                )
            }
        }

        Text(
            modifier = Modifier.weight(1f)
                .align(Alignment.CenterVertically)
                .padding(horizontal = LocalDimensions.current.xsSpacing),
            text = data.title,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            style = LocalType.current.base.bold(),
            color = getTextColor(outgoing)
        )
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
    val type: MessageType,
    val author: String,
    val displayName: Boolean = false,
    val avatar: AvatarUIData? = null,
    val status: MessageViewStatus? = null,
    val quote: MessageQuote? = null,
    val link: MessageLinkData? = null
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

data class MessageLinkData(
    val url: String,
    val title: String,
    val imageUri: String? = null
)

sealed interface MessageViewStatusIcon{
    data class DrawableIcon(@DrawableRes val icon: Int): MessageViewStatusIcon
    data object DisappearingMessageIcon: MessageViewStatusIcon
}

sealed class MessageType(){
    abstract val outgoing: Boolean
    abstract val text: AnnotatedString?

    data class Text(
        override val outgoing: Boolean,
        override val text: AnnotatedString
    ): MessageType()

    data class Media(
        override val outgoing: Boolean,
        val items: List<MessageMediaItem>,
        val loading: Boolean,
        override val text: AnnotatedString? = null
    ): MessageType()
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
            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text()
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(
                    outgoing = false,
                    text = "Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(
                    text = "Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"
                ),
                status = PreviewMessageData.sentStatus
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(
                    outgoing = false,
                    text = "Hello"
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
    DocumentMessagePreviewReuse(colors)
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
fun LinkMessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.spacing)

        ) {
            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(outgoing = false, text="Quoting text"),
                link = MessageLinkData(
                    url = "https://getsession.org/",
                    title = "Welcome to Session",
                    imageUri = null
                )
            ))


            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(text="Quoting text"),
                link = MessageLinkData(
                    url = "https://picsum.photos/id/0/367/267",
                    title = "Welcome to Session with a very long name",
                    imageUri = "https://picsum.photos/id/1/200/300"
                )
            ))

            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(outgoing = false, text="Quoting text"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                link = MessageLinkData(
                    url = "https://getsession.org/",
                    title = "Welcome to Session",
                    imageUri = null
                )
            ))


            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(text="Quoting text"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                link = MessageLinkData(
                    url = "https://picsum.photos/id/0/367/267",
                    title = "Welcome to Session with a very long name",
                    imageUri = "https://picsum.photos/id/1/200/300"
                )
            ))
        }
    }
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
    val sampleAvatar = AvatarUIData(listOf(AvatarUIElement(name = "TO", color = primaryBlue)))
    val sentStatus = MessageViewStatus(
        name = "Sent",
        icon = MessageViewStatusIcon.DrawableIcon(icon = R.drawable.ic_circle_check)
    )

    fun text(
        text: String = "Hi there",
        outgoing: Boolean = true
    ) = MessageType.Text(outgoing = outgoing, AnnotatedString(text))

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



