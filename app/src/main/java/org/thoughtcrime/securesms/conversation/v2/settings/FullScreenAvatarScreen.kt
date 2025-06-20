package org.thoughtcrime.securesms.conversation.v2.settings

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.min
import org.thoughtcrime.securesms.ui.components.AppBarCloseIcon
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.BasicAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FullscreenAvatarScreen(
    data: AvatarUIData,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onBack: () -> Unit,
){
    with(sharedTransitionScope) {
        Scaffold(
            modifier = Modifier.background(LocalColors.current.background),
            topBar = {
                BasicAppBar(
                    title = "",
                    backgroundColor = Color.Transparent,
                    navigationIcon = {
                        AppBarCloseIcon(onClose = onBack)
                    }
                )
            },
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
        ) { paddings ->
            var scale by remember {
                mutableStateOf(1f)
            }
            var offset by remember {
                mutableStateOf(Offset.Zero)
            }

            // keep only the start / end paddings
            val horizontalPadding = PaddingValues(
                start = paddings.calculateStartPadding(LayoutDirection.Ltr),
                end   = paddings.calculateEndPadding(LayoutDirection.Ltr)
            )

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(horizontalPadding),
                contentAlignment = Alignment.Center
            ) {
                // transformations states so we can pinch zoom
                val state = rememberTransformableState { zoomChange, panChange, rotationChange ->
                    scale = (scale * zoomChange).coerceIn(1f, 5f)

                    val extraWidth = (scale - 1) * constraints.maxWidth
                    val extraHeight = (scale - 1) * constraints.maxHeight

                    val maxX = extraWidth / 2
                    val maxY = extraHeight / 2

                    offset = Offset(
                        x = (offset.x + scale * panChange.x).coerceIn(-maxX, maxX),
                        y = (offset.y + scale * panChange.y).coerceIn(-maxY, maxY),
                    )
                }

                val imageSize = min(this.maxWidth, this.maxHeight)
                Avatar(
                    modifier = Modifier
                        .sharedBounds(
                            sharedTransitionScope.rememberSharedContentState(key = "avatar"),
                            animatedVisibilityScope = animatedContentScope
                        )
                        // apply other transformations
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        // add transformable to listen to multitouch transformation events
                        // after offset
                        .transformable(state = state),
                    data = data,
                    size = imageSize,
                    maxSizeLoad = imageSize,
                    clip = RectangleShape
                )
            }
        }
    }
}

@SuppressLint("UnusedContentLambdaTargetStateParameter")
@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(widthDp = 360, heightDp = 640)
@Composable
fun FullscreenAvatarScreenPreview(){
    SharedTransitionLayout {
        AnimatedContent(targetState = Unit, label = "preview") {
            FullscreenAvatarScreen(
                data = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryBlue
                        )
                    )
                ),
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedContentScope = this@AnimatedContent,
                onBack = {}
            )
        }
    }
}