package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.session.libsession.utilities.CommunityUrlParser
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.blackAlpha06

@Composable
fun CommunityInviteMessage(
    name: String,
    url: String,
    outgoing: Boolean,
    onInviteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable(onClick = { onInviteClick(url) })
            .padding(defaultMessageBubblePadding())
            .padding(vertical = LocalDimensions.current.tinySpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon background circle
        Box(
            modifier = Modifier
                .size(LocalDimensions.current.iconLarge)
                .background(
                    color = if(outgoing) blackAlpha06 else LocalColors.current.accent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    id = if(outgoing) R.drawable.ic_globe else R.drawable.ic_plus
                ),
                contentDescription = null,
                modifier = Modifier.size(LocalDimensions.current.iconSmall),
                colorFilter = ColorFilter.tint(LocalColors.current.textBubbleSent)
            )
        }

        Spacer(modifier = Modifier.width(LocalDimensions.current.smallSpacing))

        Column(
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)
        ) {
            Text(
                text = name,
                style = LocalType.current.h6,
                color = getTextColor(outgoing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = stringResource(R.string.communityInvitation),
                style = LocalType.current.base,
                color = getTextColor(outgoing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = CommunityUrlParser.trimQueryParameter(url),
                style = LocalType.current.small,
                color = getTextColor(outgoing),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Preview
@Composable
fun CommunityInvitePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {

    PreviewTheme(colors) {
        Column (
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            MessageBubble(
                color = LocalColors.current.accent
            ) {
                Column() {
                    CommunityInviteMessage(
                        name = "Test Community",
                        url = "https://www.test-community-url.com/testing-the-url-look-and-feel",
                        outgoing = true,
                        onInviteClick = {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            MessageBubble(
                color = LocalColors.current.backgroundBubbleReceived
            ) {
                Column() {
                    CommunityInviteMessage(
                        name = "Test Community",
                        url = "https://www.test-community-url.com/testing-the-url-look-and-feel",
                        outgoing = false,
                        onInviteClick = {}
                    )
                }
            }
        }
    }
}
