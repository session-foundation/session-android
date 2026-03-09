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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v3.compose.message.PreviewMessageData.quoteGroup
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.proBadgeColorOutgoing
import org.thoughtcrime.securesms.ui.proBadgeColorStandard
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
    quote: QuoteMessageData,
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
            ProBadgeText(
                text = quote.title,
                textStyle = LocalType.current.base.bold().copy(color = getTextColor(outgoing)),
                showBadge = quote.showProBadge,
                badgeColors = if(outgoing) proBadgeColorOutgoing() //todo convov3 xml quotes also checked for mode - regular here to distinguish form the quote used in the input
                else proBadgeColorStandard()
            )

            Spacer(Modifier.height(LocalDimensions.current.tinySpacing))

            //todo convov3 we shouldn't render/click links for quotes
            MessageText(
                text = quote.subtitle,
                isOutgoing = outgoing,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
                layout = MessageLayout.INCOMING,
                contentGroups = quoteGroup(text = "Quoting text")
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = quoteGroup(
                    showProBadge = true,
                    subtitle = "This is a long text efcwec wf fv d df klsdknvdslkvfds lk djvl jldfs vjldf jlkdfsv jldf jlkd jlkdf jlkdf jl kdvmkl dsfmkldmkldfmldflkdfmklfd lk mdfs fdmlkdfmklfd ml mlk mlkdf", text = "Quoting text")
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                layout = MessageLayout.INCOMING,
                contentGroups = quoteGroup(icon = MessageQuoteIcon.Icon(R.drawable.ic_file), showProBadge = true, text = "Quoting a document")
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = quoteGroup(
                    title = "You",
                    subtitle = "Audio message",
                    icon = MessageQuoteIcon.Icon(R.drawable.ic_mic),
                    text = "Quoting audio"
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = quoteGroup(icon = MessageQuoteIcon.Image("".toUri(), ""), text = "Quoting an image")
            ))
        }
    }
}