package org.thoughtcrime.securesms.conversation.v3.compose.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.GoToChoosePlan
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.border
import org.thoughtcrime.securesms.ui.sessionDropShadow
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold


@Composable
fun ConversationDateBreak(
    date: String,
    modifier: Modifier = Modifier
){
    Text(
        modifier = modifier.fillMaxWidth()
            .padding(
                top = LocalDimensions.current.xxxsSpacing,
                bottom = LocalDimensions.current.xxsSpacing
            ),
        text = date,
        color = LocalColors.current.text,
        style = LocalType.current.small.bold(),
        textAlign = TextAlign.Center
    )
}

@Composable
fun ConversationUnreadBreak(
    modifier: Modifier = Modifier
){
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(
                top = LocalDimensions.current.xxxsSpacing,
                bottom = LocalDimensions.current.xsSpacing
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
    ) {
        Box(
            modifier = Modifier.height(1.dp)
                .background(LocalColors.current.accent)
                .weight(1f),
        )

        Text(
            text = stringResource(R.string.messageUnread),
            style = LocalType.current.base.bold(),
            color = LocalColors.current.accent,
        )

        Box(
            modifier = Modifier.height(1.dp)
                .background(LocalColors.current.accent)
                .weight(1f),
        )
    }
}

@Composable
fun ScrollToBottomButton(
    unreadLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
    ) {
        // Chevron circle — aligned to bottom
        Box(
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.BottomCenter)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .background(
                    color = LocalColors.current.backgroundSecondary,
                    shape = CircleShape
                )
                .border(
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null, //todo convov3 add ax string
                modifier = Modifier.size(LocalDimensions.current.smallSpacing),
                tint = LocalColors.current.text,
            )
        }

        // Unread badge — aligned to top, overlapping the circle
        if (unreadLabel.isNotEmpty()) {
            Text(
                modifier = Modifier
                    .padding(bottom = 29.dp)
                    .shadow(
                        shape = MaterialTheme.shapes.medium,
                        elevation = 1.dp
                    )
                    .align(Alignment.TopCenter)
                    .background(
                        color = LocalColors.current.backgroundSecondary,
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(
                        horizontal = LocalDimensions.current.xxxsSpacing,
                        vertical = LocalDimensions.current.tinySpacing
                    ),
                text = unreadLabel,
                color = LocalColors.current.text,
                style = LocalType.current.small,
            )
        }
    }
}


@Preview
@Composable
fun PreviewConversationElements(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            ConversationDateBreak(date = "10:24")
            ConversationUnreadBreak()
            ScrollToBottomButton(unreadLabel = "", onClick = {})
            ScrollToBottomButton(unreadLabel = "8", onClick = {})
            ScrollToBottomButton(unreadLabel = "150", onClick = {})
            ScrollToBottomButton(unreadLabel = "9999+", onClick = {})
        }
    }
}