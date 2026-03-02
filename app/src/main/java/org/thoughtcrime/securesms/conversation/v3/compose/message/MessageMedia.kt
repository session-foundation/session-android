package org.thoughtcrime.securesms.conversation.v3.compose.message

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@Composable
fun MediaMessage(
    data: MessageType.RecipientMessage.Media,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
){
    Box(
        modifier = modifier.clip(shape = RoundedCornerShape(LocalDimensions.current.messageCornerRadius))
    ) {
        val itemSpacing: Dp = 2.dp

        when (data.items.size) {
            1 -> {
                MediaItem(
                    data = data.items[0],
                    itemSize = MediaItemSize.AspectRatio(
                        minSize = LocalDimensions.current.minMessageWidth,
                        maxSize = maxWidth,
                    ),
                )
            }

            2 -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                    ) {

                    val cellSize = maxWidth * 0.5f - itemSpacing * 0.5f

                    MediaItem(
                        data = data.items[0],
                        itemSize = MediaItemSize.SquareSize(size = cellSize),
                    )

                    MediaItem(
                        data = data.items[1],
                        itemSize = MediaItemSize.SquareSize(size = cellSize),
                    )
                }
            }

            else -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    val largeCellSize = maxWidth * 0.66f - itemSpacing * 0.5f
                    val smallCellSize = largeCellSize * 0.5f - itemSpacing * 0.5f

                    MediaItem(
                        data = data.items[0],
                        itemSize = MediaItemSize.SquareSize(size = largeCellSize),
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(itemSpacing),
                    ) {
                        MediaItem(
                            data = data.items[1],
                            itemSize = MediaItemSize.SquareSize(size = smallCellSize),
                        )

                        MediaItem(
                            data = data.items[2],
                            itemSize = MediaItemSize.SquareSize(size = smallCellSize),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaItem(
    data: MessageMediaItem,
    itemSize: MediaItemSize,
    modifier: Modifier = Modifier,
){

    var imageModifier: Modifier = modifier
        .background(LocalColors.current.backgroundSecondary)

    when(itemSize){
        is MediaItemSize.SquareSize -> {
            imageModifier = imageModifier.size(itemSize.size)
        }
        is MediaItemSize.AspectRatio -> {
            val aspectRatio = data.width / data.height.toFloat()
            val isLandscape = aspectRatio > 1f

            imageModifier = imageModifier.sizeIn(
                maxWidth = itemSize.maxSize, minWidth = itemSize.minSize,
                maxHeight = itemSize.maxSize, minHeight = itemSize.minSize
            ).aspectRatio(aspectRatio, matchHeightConstraintsFirst = !isLandscape)
        }
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(data.uri)
            .build(),
        contentDescription = data.filename,
        contentScale = ContentScale.Crop,
        modifier = imageModifier
    )
}

sealed interface MediaItemSize{
    data class SquareSize(val size: Dp): MediaItemSize
    data class AspectRatio(val minSize: Dp, val maxSize: Dp): MediaItemSize
}

@Preview
@Composable
fun MediaMessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing)
                .verticalScroll(rememberScrollState())

        ) {
            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.image(
                        width = 50,
                        height = 100
                    )),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.image(), PreviewMessageData.video()),
                    loading = false
                )
            ))


            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            var testData by remember {
                mutableStateOf(
                    MessageViewData(
                        id = MessageId(0, false),
                        displayName = "Toto",
                        type = MessageType.RecipientMessage.Media(
                            text = AnnotatedString("This also has text"),
                            outgoing = true,
                            items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                            loading = false
                        )
                    )
                )
            }

            Message(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        testData = testData.copy(highlightKey = System.currentTimeMillis())
                    }),
                data = testData
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.image(true)),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.video()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.video()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.image(), PreviewMessageData.video()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(true), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                type = MessageType.RecipientMessage.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.RecipientMessage.Media(
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.RecipientMessage.Media(
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))


            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.RecipientMessage.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = true,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                quote = PreviewMessageData.quote(icon = MessageQuoteIcon.Bar),
                type = MessageType.RecipientMessage.Media(
                    text = AnnotatedString("This also has text"),
                    outgoing = false,
                    items = listOf(PreviewMessageData.video(), PreviewMessageData.image(), PreviewMessageData.image()),
                    loading = false
                )
            ))
        }
    }
}

sealed class MessageMediaItem {
    abstract val uri: Uri
    abstract val filename: String
    abstract val loading: Boolean

    abstract val width: Int
    abstract val height: Int

    data class Image(
        override val uri: Uri,
        override val filename: String,
        override val loading: Boolean,
        override val width: Int,
        override val height: Int,
    ): MessageMediaItem()

    data class Video(
        override val uri: Uri,
        override val filename: String,
        override val loading: Boolean,
        override val width: Int,
        override val height: Int,
    ): MessageMediaItem()
}