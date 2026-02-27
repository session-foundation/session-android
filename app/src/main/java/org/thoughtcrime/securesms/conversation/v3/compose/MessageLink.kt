package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
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

data class MessageLinkData(
    val url: String,
    val title: String,
    val imageUri: String? = null
)

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
                id = MessageId(0, false),
                author = "Toto",
                type = PreviewMessageData.text(outgoing = false, text="Quoting text"),
                link = MessageLinkData(
                    url = "https://getsession.org/",
                    title = "Welcome to Session",
                    imageUri = null
                )
            ))


            Message(data = MessageViewData(
                id = MessageId(0, false),
                author = "Toto",
                type = PreviewMessageData.text(text="Quoting text"),
                link = MessageLinkData(
                    url = "https://picsum.photos/id/0/367/267",
                    title = "Welcome to Session with a very long name",
                    imageUri = "https://picsum.photos/id/1/200/300"
                )
            ))

            Message(data = MessageViewData(
                id = MessageId(0, false),
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
                id = MessageId(0, false),
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