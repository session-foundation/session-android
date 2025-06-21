package org.thoughtcrime.securesms.conversation.v3.compose

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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
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
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.theme.blackAlpha06

//todo CONVOv3 status animated icon for disappearing messages
//todo CONVOv3 highlight effect (needs to work on all types and shapes (how should it work for combos like message + image? overall effect?)
//todo CONVOv3 text formatting in bubble including mentions and links
//todo CONVOv3 images
//todo CONVOv3 audio
//todo CONVOv3 links handling
//todo CONVOv3 typing indicator
//todo CONVOv3 long press views (overlay+message+recent reactions+menu)
//todo CONVOv3 reactions
//todo CONVOv3 quotes
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
 * A message content: Bubble with content, avatar, status
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
                if (data.name != null) {
                    Text(
                        modifier = Modifier.padding(start = LocalDimensions.current.smallSpacing),
                        text = data.name,
                        style = LocalType.current.base.bold(),
                        color = LocalColors.current.text
                    )

                    Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
                }

                MessageBubble(
                    color = if (data.type.outgoing) LocalColors.current.accent
                    else LocalColors.current.backgroundBubbleReceived
                ) {
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
            maxWidth * 0.8f // 80% of available width
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
private fun getTextColor(outgoing: Boolean) = if(outgoing) LocalColors.current.textBubbleSent
else LocalColors.current.textBubbleReceived

@Composable
private fun defaultMessageBubblePadding() = PaddingValues(
    horizontal = LocalDimensions.current.smallSpacing,
    vertical = LocalDimensions.current.messageVerticalPadding
)

data class MessageViewData(
    val type: MessageType,
    val avatar: AvatarUIData? = null,
    val name: String? = null,
    val status: MessageViewStatus? = null,
    val quote: MessageViewData? = null,
)

data class MessageViewStatus(
    val name: String,
    val icon: MessageViewStatusIcon
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
        val loading: Boolean
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
                type = MessageType.Text(outgoing = true, AnnotatedString("Hi there"))
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                name = "Toto",
                avatar = AvatarUIData(listOf(AvatarUIElement(name = "TO", color = primaryBlue))),
                type = MessageType.Text(outgoing = false, AnnotatedString("Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"))
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                type = MessageType.Text(outgoing = true, AnnotatedString("Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?")),
                status = MessageViewStatus(
                    name = "Sent",
                    icon = MessageViewStatusIcon.DrawableIcon(icon = R.drawable.ic_circle_check)
                )
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
                type = MessageType.Document(
                    outgoing = true,
                    name = "Document",
                    size = "5.4MB",
                    loading = false
                ))
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                name = "Toto",
                avatar = AvatarUIData(listOf(AvatarUIElement(name = "TO", color = primaryBlue))),
                type = MessageType.Document(
                    outgoing = false,
                    name = "Document with a really long name that should ellepsize once it reaches the max width",
                    size = "5.4MB",
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                type = MessageType.Document(
                    outgoing = true,
                    name = "Another Document",
                    size = "7.8MB",
                    loading = true
                ))
            )
        }
    }
}

