package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.classicDark3
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.util.AvatarBadge
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement
import org.thoughtcrime.securesms.util.avatarOptions


@Composable
fun BaseAvatar(
    size: Dp,
    data: AvatarUIData,
    modifier: Modifier = Modifier,
    clip: Shape = CircleShape,
    maxSizeLoad: Dp = LocalDimensions.current.iconLarge,
    badge: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.size(size)) {

        when {
            data.elements.isEmpty() -> {
                // Do nothing when there is no avatar data.
            }
            data.elements.size == 1 -> {
                // Only one element, occupy the full parent's size.
                AvatarElement(
                    size = size,
                    data = data.elements.first(),
                    clip = clip,
                    maxSizeLoad = maxSizeLoad
                )
            }
            else -> {
                // Two or more elements: show the first two in a staggered layout.
                val avatarSize = size * 0.78f
                AvatarElement(
                    modifier = Modifier.align(Alignment.TopStart),
                    size = avatarSize,
                    data = data.elements[0],
                    clip = clip,
                    maxSizeLoad = maxSizeLoad
                )
                AvatarElement(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    size = avatarSize,
                    data = data.elements[1],
                    clip = clip,
                    maxSizeLoad = maxSizeLoad
                )
            }
        }

        // Badge content, if any.
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(1.dp, 1.dp) // Used to make up for transparent padding in icon.
                    .size(size * 0.4f)
            ) {
                badge()
            }
        }
    }
}

@Composable
fun Avatar(
    size: Dp,
    data: AvatarUIData,
    modifier: Modifier = Modifier,
    clip: Shape = CircleShape,
    maxSizeLoad: Dp = LocalDimensions.current.iconLarge,
    badge: AvatarBadge = AvatarBadge.None,
){
    BaseAvatar(
        size = size,
        modifier = modifier,
        data = data,
        clip = clip,
        maxSizeLoad = maxSizeLoad,
        badge = when (badge) {
                AvatarBadge.None -> null

                else -> {
                    {
                        Image(
                            painter = painterResource(id = badge.icon),
                            contentDescription = null,
                        )
                    }
                }
            }
    )
}

@Composable
private fun AvatarElement(
    size: Dp,
    modifier: Modifier = Modifier,
    data: AvatarUIElement,
    clip: Shape = CircleShape,
    maxSizeLoad: Dp = LocalDimensions.current.iconLarge,
){
    // first attempt to display the custom image if there is one
    if (data.remoteFile != null){
        val maxSizePx = with(LocalDensity.current) {
            maxSizeLoad.toPx().toInt().coerceAtLeast(size.toPx().toInt())
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(data.remoteFile)
                .allowRgb565(true)
                .avatarOptions(maxSizePx, freezeFrame = data.freezeFrame)
                .build(),
            modifier = modifier.size(size).clip(clip),
            contentDescription = null,
            contentScale = ContentScale.Crop
        ) {
            val scope = rememberCoroutineScope()

            val painterState = remember(painter.state) {
                painter.state
                    .transform { value ->
                        if (value is AsyncImagePainter.State.Loading) {
                            delay(200L) // Delay to avoid flickering when loading
                        }

                        emit(value)
                    }
                    .stateIn(scope, SharingStarted.Eagerly, painter.state.value)
            }

            val state by painterState.collectAsState()

            when (state) {
                is AsyncImagePainter.State.Success -> {
                    SubcomposeAsyncImageContent()
                }

                AsyncImagePainter.State.Empty -> {
                    // We should not show the fallback just in case we can
                    // load the image very soon so we don't need to see the fallback
                }

                is AsyncImagePainter.State.Error,
                is AsyncImagePainter.State.Loading -> {
                    FallbackIcon(modifier = Modifier.fillMaxSize(), clip = clip, size = size, data = data)
                }
            }
        }
    } else { // second attempt to use the custom icon if there is one
        FallbackIcon(modifier = modifier, clip = clip, size = size, data = data)

    }
}

/**
 * Fallback image for teh avatar in case there is no custom file for it
 */
@Composable
private fun FallbackIcon(
    modifier: Modifier,
    size: Dp,
    clip: Shape,
    data: AvatarUIElement,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = data.color ?: classicDark3,
                shape = clip,
            )
            .clip(clip),
    ) {
        if (data.icon != null) {
            Image(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.2f),
                painter = painterResource(id = data.icon),
                colorFilter = ColorFilter.tint(Color.White),
                contentDescription = null,
            )
        } // third, try to use the name if there is one
        else if (!data.name.isNullOrEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    modifier = Modifier.padding(size * 0.2f),
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 6.sp
                    ),
                    text = data.name,
                    style = LocalType.current.base.copy(
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1
                )
            }
        } else { // no name nor image data > show the default unknown icon
            Image(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.2f),
                painter = painterResource(id = R.drawable.ic_user_filled_custom),
                contentDescription = null,
            )
        }
    }
}

@Preview
@Composable
fun PreviewAvatarElement(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
){
    PreviewTheme(colors) {
        AvatarElement(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIElement(
                name = "TO",
                color = primaryGreen,
                remoteFile = null
            )
        )
    }
}

@Preview
@Composable
fun PreviewAvatarSingleAdmin(){
    PreviewTheme {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIData(
                listOf(AvatarUIElement(
                name = "AT",
                color = primaryGreen,
                remoteFile = null
            ))),
            badge = AvatarBadge.Admin
        )
    }
}

@Preview
@Composable
fun PreviewAvatarDouble(){
    PreviewTheme {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIData(
                listOf(AvatarUIElement(
                    name = "FR",
                    color = primaryGreen,
                    remoteFile = null
                ),
                AvatarUIElement(
                    name = "AT",
                    color = primaryBlue,
                    remoteFile = null
                )
            ))
        )
    }
}

@Preview
@Composable
fun PreviewAvatarSingleUnknown(){
    PreviewTheme {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIData(
                listOf(AvatarUIElement(
                name = "",
                color = null,
                remoteFile = null
            )))
        )
    }
}

@Preview
@Composable
fun PreviewAvatarIconNoName(){
    PreviewTheme {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIData(
                listOf(AvatarUIElement(
                    name = "",
                    icon = R.drawable.session_logo,
                    color = null,
                    remoteFile = null
                )))
        )
    }
}

@Preview
@Composable
fun PreviewAvatarIconWithName(){
    PreviewTheme {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIData(
                listOf(AvatarUIElement(
                    name = "TO",
                    icon = R.drawable.session_logo,
                    color = null,
                    remoteFile = null
                )))
        )
    }
}

@Preview
@Composable
fun PreviewAvatarSinglePhoto(){
    PreviewTheme {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIData(
                listOf(AvatarUIElement(
                name = "AT",
                color = primaryGreen,
                remoteFile = null
            )))
        )
    }
}

@Preview
@Composable
fun PreviewAvatarElementUnclipped(){
    PreviewTheme {
        AvatarElement(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIElement(
                name = "TO",
                color = primaryGreen,
                remoteFile = null
            ),
            clip = RectangleShape
        )
    }
}
