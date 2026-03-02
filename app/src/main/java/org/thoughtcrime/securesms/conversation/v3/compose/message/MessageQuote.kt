package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import network.loki.messenger.R
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.blackAlpha06
import org.thoughtcrime.securesms.ui.theme.bold

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
                            shape = RoundedCornerShape(LocalDimensions.current.shapeXXSmall)
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
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(quote.icon.uri)
                        .build(),
                    contentDescription = quote.icon.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .background(
                            color = blackAlpha06,
                            shape = RoundedCornerShape(LocalDimensions.current.shapeXXSmall)
                        )
                        .size(LocalDimensions.current.quoteIconSize)
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
                id = MessageId(0, false),
                displayName = "Toto",
                type = PreviewMessageData.text(outgoing = false, text="Quoting text"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar)
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = PreviewMessageData.text(text="Quoting text"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar)
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.text(outgoing = false, text="Quoting a document"),
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Icon(R.drawable.ic_file))
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Text(outgoing = true, AnnotatedString("Quoting audio")),
                quote = PreviewMessageData.quote(
                    title = "You",
                    subtitle = "Audio message",
                    icon = MessageQuoteIcon.Icon(R.drawable.ic_mic)
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Text(outgoing = true, AnnotatedString("Quoting an image")),
                quote = PreviewMessageData.quote(icon = PreviewMessageData.quoteImage())
            ))

        }
    }
}