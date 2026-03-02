package org.thoughtcrime.securesms.conversation.v3.compose.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.Divider
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
            .padding(vertical = LocalDimensions.current.xxxsSpacing),
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
                bottom = LocalDimensions.current.smallSpacing
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
        }
    }
}