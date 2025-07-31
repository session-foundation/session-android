package org.thoughtcrime.securesms.conversation.v3.compose

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import okhttp3.internal.ws.MessageInflater
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@Composable
fun MediaMessage(
    data: MessageType.Media,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
){
    Box(
        modifier = modifier.clip(shape = RoundedCornerShape(LocalDimensions.current.messageCornerRadius))
    ) {
        CALCULATE IMAGE SIZES - DYNAMIC FOR ONE BUT WITH A MIN
        SET SIZE FOR 2 AND 3 
        when (data.items.size) {
            1 -> {
                MediaItem(
                    data = data.items[0],
                    maxWidth = maxWidth,
                    minSize = LocalDimensions.current.minMessageWidth
                )
            }

            2 -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                    MediaItem(
                        data = data.items[0],
                        maxWidth = maxWidth,
                        minSize = LocalDimensions.current.minMessageWidth * 0.5f
                    )

                    MediaItem(
                        data = data.items[1],
                        maxWidth = maxWidth,
                        minSize = LocalDimensions.current.minMessageWidth * 0.5f
                    )
                }
            }

            else -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MediaItem(
                        data = data.items[0],
                        maxWidth = maxWidth,
                        minSize = LocalDimensions.current.minMessageWidth * 0.5f
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MediaItem(
                            data = data.items[1],
                            maxWidth = maxWidth,
                            minSize = LocalDimensions.current.minMessageWidth * 0.5f
                        )

                        MediaItem(
                            data = data.items[2],
                            maxWidth = maxWidth,
                            minSize = LocalDimensions.current.minMessageWidth * 0.5f
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun MediaItem(
    data: MessageMediaItem,
    modifier: Modifier = Modifier,
    maxWidth: Dp,
    minSize: Dp
){
    //todo CONVOv3 media items (1 vs 2 vs 3 items)
    GlideImage(
        contentScale = ContentScale.Crop,
        modifier = modifier.widthIn(max = maxWidth, min = minSize)
            .heightIn(max = maxWidth, min = minSize) // the image can only be as tall as the max width
            .background(LocalColors.current.backgroundSecondary),
        model = DecryptableStreamUriLoader.DecryptableUri(data.uri),
        contentDescription = data.filename
    )
}

@Preview
@Composable
fun MediaMessagePreviewLocal(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing)
                .verticalScroll(rememberScrollState())

        ) {
            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.image(), PreviewMessageData.video()),
                    loading = false
                )
            ))


            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.image(true)),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.video()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.video()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.image(), PreviewMessageData.video()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(true), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = MessageType.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))


            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))
        }
    }
}

sealed class MessageMediaItem(){
    abstract val uri: Uri
    abstract val filename: String
    abstract val loading: Boolean

    data class Image(
        override val uri: Uri,
        override val filename: String,
        override val loading: Boolean,
    ): MessageMediaItem()

    data class Video(
        override val uri: Uri,
        override val filename: String,
        override val loading: Boolean,
    ): MessageMediaItem()
}