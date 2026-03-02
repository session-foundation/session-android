package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType.Companion.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.blackAlpha06
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.bold

@Composable
fun ColumnScope.CommunityInviteMessage(
    data: MessageViewData,
    type: MessageType.RecipientMessage.CommunityInvite,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(defaultMessageBubblePadding()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon background circle
        Box(
            modifier = Modifier
                .size(LocalDimensions.current.iconLarge)
                .background(
                    color = if(type.outgoing) blackAlpha06 else LocalColors.current.accent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    id = if(type.outgoing) R.drawable.ic_globe else R.drawable.ic_plus
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
                text = type.communityName,
                style = LocalType.current.h6,
                color = getTextColor(type.outgoing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = stringResource(R.string.communityInvitation),
                style = LocalType.current.base,
                color = getTextColor(type.outgoing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = type.url,
                style = LocalType.current.small,
                color = getTextColor(type.outgoing),
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
    val outgoingInvite = MessageViewData(
        id = MessageId(0, false),
        displayName = "Toto",
        type = PreviewMessageData.communityInvite()
    )

    val incomingInvite = MessageViewData(
        id = MessageId(0, false),
        displayName = "Toto",
        type = PreviewMessageData.communityInvite(
            outgoing = false
        )
    )


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
                        data = outgoingInvite,
                        type = outgoingInvite.type as MessageType.RecipientMessage.CommunityInvite
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            MessageBubble(
                color = LocalColors.current.backgroundBubbleReceived
            ) {
                Column() {
                    CommunityInviteMessage(
                        data = incomingInvite,
                        type = incomingInvite.type as MessageType.RecipientMessage.CommunityInvite
                    )
                }
            }
        }
    }
}