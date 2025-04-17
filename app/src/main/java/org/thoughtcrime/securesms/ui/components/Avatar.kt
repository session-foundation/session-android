package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import network.loki.messenger.R
import org.session.libsession.avatars.ContactPhoto
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.groups.compose.AutoResizeText
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.classicLight1
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.util.AvatarBadge
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@Composable
fun BaseAvatar(
    size: Dp,
    data: AvatarUIData,
    modifier: Modifier = Modifier,
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
                    data = data.elements.first()
                )
            }
            else -> {
                // Two or more elements: show the first two in a staggered layout.
                val avatarSize = size * 0.78f
                AvatarElement(
                    modifier = Modifier.align(Alignment.TopStart),
                    size = avatarSize,
                    data = data.elements[0]
                )
                AvatarElement(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    size = avatarSize,
                    data = data.elements[1]
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
    badge: AvatarBadge = AvatarBadge.None,
){
    BaseAvatar(
        size = size,
        modifier = modifier,
        data = data,
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

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AvatarElement(
    size: Dp,
    modifier: Modifier = Modifier,
    data: AvatarUIElement
){
    Box(
        modifier = modifier.size(size)
            .background(
                color = data.color ?: classicLight1,
                shape = CircleShape,
            )
            .clip(CircleShape),
    ) {
        if(data.contactPhoto != null){
            GlideImage(
                model = data.contactPhoto,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                transition = CrossFade,
                loading = placeholder(R.drawable.ic_user_filled_custom_padded),
                requestBuilderTransform = {
                    it.diskCacheStrategy(DiskCacheStrategy.NONE)
                        .centerCrop()
                        .circleCrop()
                }
            )
        } else if(!data.name.isNullOrEmpty()){
            AutoResizeText(
                modifier = Modifier.padding(size * 0.2f),
                text = data.name,
                style = LocalType.current.base
            )
        } else { // no name nor image data > show the default unknown icon
            Image(
                modifier = Modifier.fillMaxSize().padding(size * 0.2f),
                painter = painterResource(id = R.drawable.ic_user_filled_custom),
                contentDescription = null,
            )
        }
    }
}

@Preview
@Composable
fun PreviewAvatarElement(){
    PreviewTheme {
        AvatarElement(
            size = LocalDimensions.current.iconLarge,
            data = AvatarUIElement(
                name = "TO",
                color = primaryGreen,
                contactPhoto = null
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
                contactPhoto = null
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
                    contactPhoto = null
                ),
                AvatarUIElement(
                    name = "AT",
                    color = primaryBlue,
                    contactPhoto = null
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
                contactPhoto = null
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
                contactPhoto = ProfileContactPhoto(
                    Address.fromSerialized("05c0d6db0f2d400c392a745105dc93b666642b9dd43993e97c2c4d7440c453b620"),
                    "305422957"
                )
            )))
        )
    }
}
