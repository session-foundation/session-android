package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

private val playPauseSize = 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMessage(
    data: Audio,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LocalDimensions.current.xxsSpacing),
        
    ) {

        val textColor = getTextColor(data.outgoing)

        val (color1, color2, trackEmptyColor) = if (data.outgoing) {
            arrayOf(
                LocalColors.current.backgroundSecondary,  // bg secondary
                LocalColors.current.text, // text primary
                LocalColors.current.backgroundSecondary.copy(alpha = 0.5f)
            )
        } else {
            arrayOf(
                LocalColors.current.accent,  // accent
                LocalColors.current.background, // background primary
                LocalColors.current.textSecondary // text secondary

            )
        }

        val playPauseSpacing = LocalDimensions.current.smallSpacing + playPauseSize + LocalDimensions.current.smallSpacing // aligns with slider start after play button

        // Title
        Text(
            modifier = Modifier
                .padding(start = playPauseSpacing, end = LocalDimensions.current.smallSpacing),
            text = data.title,
            style = LocalType.current.small.copy(fontStyle = FontStyle.Italic),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // play + seek
        Row(
            modifier = Modifier
                .padding(horizontal = LocalDimensions.current.xsSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            PlayPauseButton(
                isPlaying = data.isPlaying,
                showLoader = data.showLoader,
                bgColor = color1,
                iconColor = color2,
                onClick = {
                    //todo CONVOV3 implement
                }
            )

            // Slider acts like SeekBar
            val progress =
                if (data.durationMs > 0) (data.positionMs.toFloat() / data.durationMs.toFloat())
                else 0f

            Slider(
                modifier = Modifier.weight(1f),
                value = progress.coerceIn(0f, 1f),
                onValueChange = {
                    //todo CONVOV3 implement
                },
                enabled = !data.showLoader,
                valueRange = 0f..1f,
                thumb = { source ->
                    SliderDefaults.Thumb(
                        interactionSource = remember { MutableInteractionSource() },
                        colors = SliderDefaults.colors(thumbColor = color1),
                        thumbSize = DpSize(4.dp, 20.dp)
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(10.dp),
                        drawStopIndicator = null,
                        thumbTrackGapSize = 2.dp,
                        colors = SliderDefaults.colors(
                            activeTrackColor = color1,
                            inactiveTrackColor = trackEmptyColor
                        )
                    )
                }
            )
        }

        // Bottom: speed chip + remaining
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = playPauseSpacing,
                    end = LocalDimensions.current.smallSpacing
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PlaybackSpeedButton(
                text = data.speedText,
                bgColor = if (data.outgoing) color1 else color2,
                textColor = if(data.outgoing) color2 else textColor,
                onClick = {
                    //todo CONVOV3 implement
                }
            )

            Text(
                text = data.remainingText,
                style = LocalType.current.small,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    showLoader: Boolean,
    bgColor: Color,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {

    Box(
        modifier = modifier
            .size(playPauseSize)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (showLoader) {
            SmallCircularProgressIndicator(color = iconColor)
        } else {
            Image(
                painter = painterResource(
                    id = if (isPlaying) R.drawable.pause else R.drawable.play
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}


@Composable
private fun PlaybackSpeedButton(
    text: String,
    bgColor: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(LocalDimensions.current.shapeXXSmall))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(
                horizontal = LocalDimensions.current.xxsSpacing,
                vertical = LocalDimensions.current.xxxsSpacing
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = LocalType.current.small,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

data class Audio(
    override val outgoing: Boolean,
    override val text: AnnotatedString? = null,
    val title: String,
    val speedText: String,
    val remainingText: String,
    val durationMs: Long,       // slider max reference
    val positionMs: Long,       // slider position
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean,
    val showLoader: Boolean,
) : MessageType()

@Preview
@Composable
fun AudioMessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing)

        ) {
            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.audio()
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                type = PreviewMessageData.audio(
                    outgoing = false,
                    title = "Audio with a really long name that should ellipsize once it reaches the max width",
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                author = "Toto",
                type = PreviewMessageData.audio(
                    playing = false
                )
            ))
        }
    }
}