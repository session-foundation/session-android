package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
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

//todo CONVOv3 status
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
//todo CONVOv3 document view
//todo CONVOv3 attachment controls
//todo CONVOv3 deleted messages
//todo CONVOv3 swipe to reply
//todo CONVOv3 inputbar quote/reply
//todo CONVOv3 proper accessibility on overall message control

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
            .padding(
                horizontal = LocalDimensions.current.smallSpacing,
                vertical = LocalDimensions.current.messageVerticalPadding
            )
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
                    color = if (data.type.outgoing) LocalColors.current.primary
                    else LocalColors.current.backgroundBubbleReceived
                ) {
                    // Apply content based on message type
                    when (data.type) {
                        // Text messages
                        is MessageType.Text -> MessageText(
                            data = data.type
                        )
                    }
                }
            }
        }

        // status
        if (data.status != null) {

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
fun MessageText(
    data: MessageType.Text,
    modifier: Modifier = Modifier
){
    Text(
        modifier = modifier,
        text = data.text,
        style = LocalType.current.large,
        color = if(data.outgoing) LocalColors.current.textBubbleSent else LocalColors.current.textBubbleReceived,
    )
}

data class MessageViewData(
    val avatar: AvatarUIData? = null,
    val name: String? = null,
    val status: MessageViewStatus? = null,
    val type: MessageType
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
}

@PreviewScreenSizes
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
                type = MessageType.Text(outgoing = true, AnnotatedString("Hello, this is a message with multiple lines To test out styling and making sure it looks good but also continues for even longer as we are testing various screen width and I need to see how far it will go before reaching the max available width so there is a lot to say but also none of this needs to mean anything and yet here we are, are you still reading this by the way?"))
            ))
        }
    }
}