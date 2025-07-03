@file:OptIn(ExperimentalGlideComposeApi::class)

package org.thoughtcrime.securesms.conversation.v3.compose

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.net.toUri
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import network.loki.messenger.R
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
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
//todo CONVOv3 images
//todo CONVOv3 audio
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
    modifier: Modifier = Modifier
) {
    //todo CONVOv3 update composable in Landing

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

            Column {
                if (data.displayName) {
                    Text(
                        modifier = Modifier.padding(start = LocalDimensions.current.smallSpacing),
                        text = data.author,
                        style = LocalType.current.base.bold(),
                        color = LocalColors.current.text
                    )

                    Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
                }

                MessageBubble(
                    color = if (data.type.outgoing) LocalColors.current.accent
                    else LocalColors.current.backgroundBubbleReceived
                ) {
                    Column {
                        // Display quote if there is one
                        if(data.quote != null){
                            MessageQuote(
                                outgoing = data.type.outgoing,
                                quote = data.quote
                            )
                        }

                        // display link data if any
                        if(data.link != null){
                            MessageLink(
                                modifier = Modifier.padding(top = if(data.quote != null) LocalDimensions.current.xxsSpacing else 0.dp),
                                data = data.link,
                                outgoing = data.type.outgoing
                            )
                        }

                        // Apply content based on message type
                        when (data.type) {
                            // Text messages
                            is MessageType.Text -> MessageText(
                                modifier = Modifier.padding(defaultMessageBubblePadding()),
                                data = data.type
                            )

                            // Document messages
                            is MessageType.Document -> DocumentMessage(
                                data = data.type
                            )

                            // Audio messages
                            is MessageType.Audio -> {
                                //todo CONVOv3 audio message
                            }

                            // Media messages
                            is MessageType.Media -> {
                                //todo CONVOv3 media message
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
            data = data
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
fun MessageQuote(
    outgoing: Boolean,
    quote: MessageQuote,
    modifier: Modifier = Modifier
){
    Row(
        modifier = modifier.height(IntrinsicSize.Min)
            .padding(horizontal = LocalDimensions.current.xsSpacing)
            .padding(top = LocalDimensions.current.xsSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
    ) {
        // icon
        when(quote.icon){
            is MessageQuoteIcon.Bar -> {
                Box(
                    modifier = Modifier.fillMaxHeight()
                        .background(color = if(outgoing) LocalColors.current.textBubbleSent else LocalColors.current.accent)
                        .width(4.dp),
                )
            }

            is MessageQuoteIcon.Icon -> {
                Box(
                    modifier = Modifier.fillMaxHeight()
                        .background(
                            color = blackAlpha06,
                            shape = RoundedCornerShape(LocalDimensions.current.shapeXXS)
                        )
                        .size(LocalDimensions.current.quoteIconSize),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = quote.icon.icon),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(getTextColor(outgoing)),
                        modifier = Modifier.align(Alignment.Center).size(LocalDimensions.current.iconMedium)
                    )
                }
            }

            is MessageQuoteIcon.Image -> {
                GlideImage(
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.background(
                        color = blackAlpha06,
                        shape = RoundedCornerShape(LocalDimensions.current.shapeXXS)
                    ).size(LocalDimensions.current.quoteIconSize),
                    model = DecryptableStreamUriLoader.DecryptableUri(quote.icon.uri),
                    contentDescription = quote.icon.filename
                )
            }
        }

        Column{
            Text(
                text = quote.title,
                style = LocalType.current.base.bold(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = getTextColor(outgoing)
            )

            Text(
                text = quote.subtitle,
                style = LocalType.current.base,
                color = getTextColor(outgoing)
            )
        }
    }
}

@Composable
fun MessageText(
    data: MessageType.Text,
    modifier: Modifier = Modifier
){
    Text(
        modifier = modifier,
        text = data.text,
        style = LocalType.current.large,
        color = getTextColor(data.outgoing),
    )
}

@Composable
fun DocumentMessage(
    data: MessageType.Document,
    modifier: Modifier = Modifier
){
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
    ) {
        // icon box
        Box(
            modifier = Modifier.fillMaxHeight()
                .background(blackAlpha06)
                .padding(horizontal = LocalDimensions.current.xsSpacing),
            contentAlignment = Alignment.Center
        ) {
            if(data.loading){
                SmallCircularProgressIndicator(color = getTextColor(data.outgoing))
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_file),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(getTextColor(data.outgoing)),
                    modifier = Modifier.align(Alignment.Center).size(LocalDimensions.current.iconMedium)
                )
            }
        }

        val padding = defaultMessageBubblePadding()
        Column(
            modifier = Modifier.padding(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
                end = padding.calculateEndPadding(LocalLayoutDirection.current)
            )
        ) {
            Text(
                text = data.name,
                style = LocalType.current.large,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = getTextColor(data.outgoing)
            )

            Text(
                text = data.size,
                style = LocalType.current.small,
                color = getTextColor(data.outgoing)
            )
        }
    }
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
                GlideImage(
                    model = data.imageUri,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                    transition = CrossFade,
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
private fun getTextColor(outgoing: Boolean) = if(outgoing) LocalColors.current.textBubbleSent
else LocalColors.current.textBubbleReceived

@Composable
private fun defaultMessageBubblePadding() = PaddingValues(
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

sealed class MessageQuoteIcon(){
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
    data class Text(
        override val outgoing: Boolean,
        val text: AnnotatedString
    ): MessageType()

    data class Document(
        override val outgoing: Boolean,
        val name: String,
        val size: String,
        val uri: String,
        val loading: Boolean
    ): MessageType()

    data class Audio(
        override val outgoing: Boolean,
        val name: String,
        val time: String,
        val uri: String,
        val progress: Float,
        val audioState: MessageAudioState
    ): MessageType()

    data class Media(
        override val outgoing: Boolean,
        val items: List<MessageMediaItem>,
        val loading: Boolean
    ): MessageType()
}

sealed class MessageMediaItem(){
    abstract val uri: String
    abstract val filename: String

    data class Image(
        override val uri: String,
        override val filename: String
    ): MessageMediaItem()

    data class Video(
        override val uri: String,
        override val filename: String
    ): MessageMediaItem()
}

sealed class MessageAudioState(){
    data object Loading: MessageAudioState()
    data object Playing: MessageAudioState()
    data object Paused: MessageAudioState()
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
        }
    }
}

@Preview
@Composable
fun DocumentMessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing)

        ) {
            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.document()
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.document(
                    outgoing = false,
                    name = "Document with a really long name that should ellepsize once it reaches the max width"
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.document(
                    loading = true
                ))
            )
        }
    }
}

@Preview
@Composable
fun QuoteMessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing)

        ) {
            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(outgoing = false, text="Quoting text"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar)
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.text(text="Quoting text"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar)
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(outgoing = false, text="Quoting a document"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Icon(R.drawable.ic_file))
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Text(outgoing = true, AnnotatedString("Quoting audio")),
                quote = PreviewMessageData.quote(
                    title = "You",
                    subtitle = "Audio message",
                    icon = MessageQuoteIcon.Icon(R.drawable.ic_mic)
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Text(outgoing = true, AnnotatedString("Quoting an image")),
                quote = PreviewMessageData.quote(icon = PreviewMessageData.quoteImage())
            ))
        }
    }
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

private object PreviewMessageData {

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
        name: String = "Document",
        size: String = "5.4MB",
        outgoing: Boolean = true,
        loading: Boolean = false
    ) = MessageType.Document(
        outgoing = outgoing,
        name = name,
        size = size,
        loading = loading,
        uri = ""
    )

    fun audio(
        name: String = "Audio",
        time: String = "1:23",
        outgoing: Boolean = true,
        progress: Float = 0.5f,
        state: MessageAudioState = MessageAudioState.Playing
    ) = MessageType.Audio(
        outgoing = outgoing,
        name = name,
        time = time,
        uri = "",
        progress = progress,
        audioState = state
    )

    fun image() = MessageMediaItem.Image(
        "",
        ""
    )

    fun video() = MessageMediaItem.Video(
        "",
        ""
    )

    fun quote(
        title: String = "Toto",
        subtitle: String = "This is a quote",
        icon: MessageQuoteIcon = MessageQuoteIcon.Bar
    ) = MessageQuote(
        title = title,
        subtitle = subtitle
        ,
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



