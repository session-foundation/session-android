package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

//todo CONVOv3 basic bubble
//todo CONVOv3 basic message structure (bubble+avatar+name+status)
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
        modifier = modifier.background(
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
 * A message: Bubble with content, avatar, status
 */
@Composable
fun Message(
    messageType: MessageType,
    modifier: Modifier = Modifier
) {
    //todo CONVOv3 handle start/end positioning based on outgoing/incoming
    //todo CONVOv3 handle max width
    //todo CONVOv3 update composable in Landing
    Column(
        modifier = modifier
    ) {
        MessageBubble(
            color = if(messageType.outgoing) LocalColors.current.primary
            else LocalColors.current.backgroundBubbleReceived
        ) {
            // Apply content based on message type
            when(messageType) {
                // Text messages
                is MessageType.Text -> MessageText(
                    data = messageType
                )
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
        color = if(data.outgoing) LocalColors.current.textBubbleSent else LocalColors.current.textBubbleReceived,
    )
}

sealed class MessageType(){
    abstract val outgoing: Boolean
    data class Text(
        override val outgoing: Boolean,
        val text: AnnotatedString
    ): MessageType()
}

@Preview
@Composable
fun MessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.width(600.dp).padding(LocalDimensions.current.spacing)

        ) {
            Message(messageType = MessageType.Text(outgoing = true, AnnotatedString("Hi there")))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(messageType = MessageType.Text(outgoing = false, AnnotatedString("Hello, this is a message with multiple lines To test out styling and making sure it looks good")))
        }
    }
}