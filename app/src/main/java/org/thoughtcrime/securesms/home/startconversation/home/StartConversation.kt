package org.thoughtcrime.securesms.home.startconversation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import network.loki.messenger.R
import org.thoughtcrime.securesms.home.startconversation.StartConversationDestination
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.ItemButton
import org.thoughtcrime.securesms.ui.adaptive.rememberTwoPane
import org.thoughtcrime.securesms.ui.components.AppBarCloseIcon
import org.thoughtcrime.securesms.ui.components.BasicAppBar
import org.thoughtcrime.securesms.ui.components.QrImage
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartConversationScreen(
    accountId: String,
    navigateTo: (StartConversationDestination) -> Unit,
    onClose: () -> Unit,
) {
    val isTwoPane = rememberTwoPane()

    Column(
        modifier = Modifier.background(
            LocalColors.current.backgroundSecondary,
            shape = MaterialTheme.shapes.small.copy(
                bottomStart = CornerSize(0.dp),
                bottomEnd = CornerSize(0.dp)
            )
        )
    ) {
        BasicAppBar(
            title = stringResource(R.string.conversationsStart),
            backgroundColor = Color.Transparent, // transparent to show the rounded shape of the container
            actions = { AppBarCloseIcon(onClose = onClose) },
            windowInsets = WindowInsets(0, 0, 0, 0), // Insets handled by the dialog
        )
        Surface(
            modifier = Modifier.nestedScroll(rememberNestedScrollInteropConnection()),
            color = LocalColors.current.backgroundSecondary
        ) {
            if (isTwoPane) {
                TwoPaneContent(accountId, navigateTo)
            } else {
                PortraitContent(accountId, navigateTo)
            }
        }
    }
}

@Composable
private fun PortraitContent(
    accountId: String,
    navigateTo: (StartConversationDestination) -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        ActionList(navigateTo = navigateTo)
        Column(
            modifier = Modifier
                .padding(horizontal = LocalDimensions.current.spacing)
                .padding(top = LocalDimensions.current.spacing)
                .padding(bottom = LocalDimensions.current.spacing)
        ) {
            Text(stringResource(R.string.accountIdYours), style = LocalType.current.xl)
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
            Text(
                text = stringResource(R.string.qrYoursDescription),
                color = LocalColors.current.textSecondary,
                style = LocalType.current.small
            )
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
            QrImage(
                string = accountId,
                Modifier.qaTag(R.string.AccessibilityId_qrCode),
                icon = R.drawable.session
            )
        }
    }
}

@Composable
private fun TwoPaneContent(
    accountId: String,
    navigateTo: (StartConversationDestination) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalDimensions.current.spacing),
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.spacing)
    ) {
        // Left: independently scrollable actions list
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            ActionList(navigateTo = navigateTo)
        }

        // Right: QR panel, vertically centered, with square sizing
        QrPanel(
            accountId = accountId,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .align(Alignment.CenterVertically)
        )
    }
}

@Composable
private fun QrPanel(
    accountId: String,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
    ) {
        // Fit the QR inside available height/width of the right rail
        val shortest: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val qrSide = (shortest * 0.72f).coerceIn(160.dp, 520.dp)

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.accountIdYours), style = LocalType.current.xl)
            Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
            Text(
                text = stringResource(R.string.qrYoursDescription),
                color = LocalColors.current.textSecondary,
                style = LocalType.current.small
            )
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
            QrImage(
                string = accountId,
                modifier = Modifier
                    .size(qrSide)
                    .qaTag(R.string.AccessibilityId_qrCode),
                icon = R.drawable.session
            )
        }
    }
}

@Composable
private fun ActionList(navigateTo: (StartConversationDestination) -> Unit) {
    val context = LocalContext.current

    val dividerIndent: Dp =
        LocalDimensions.current.itemButtonIconSpacing + 2 * LocalDimensions.current.smallSpacing
    val newMessageTitleTxt: String = context.resources.getQuantityString(R.plurals.messageNew, 1, 1)
    val itemHeight = 50.dp

    ItemButton(
        text = annotatedStringResource(newMessageTitleTxt),
        textStyle = LocalType.current.xl,
        iconRes = R.drawable.ic_message_square,
        iconSize = LocalDimensions.current.iconMedium,
        minHeight = itemHeight,
        modifier = Modifier.qaTag(R.string.AccessibilityId_messageNew),
        onClick = {
            navigateTo(StartConversationDestination.NewMessage)
        }
    )
    Divider(
        paddingValues = PaddingValues(
            start = dividerIndent,
            end = LocalDimensions.current.smallSpacing
        )
    )
    ItemButton(
        text = annotatedStringResource(R.string.groupCreate),
        textStyle = LocalType.current.xl,
        iconRes = R.drawable.ic_users_group_custom,
        iconSize = LocalDimensions.current.iconMedium,
        minHeight = itemHeight,
        modifier = Modifier.qaTag(R.string.AccessibilityId_groupCreate),
        onClick = {
            navigateTo(StartConversationDestination.CreateGroup)
        }
    )
    Divider(
        paddingValues = PaddingValues(
            start = dividerIndent,
            end = LocalDimensions.current.smallSpacing
        )
    )
    ItemButton(
        text = annotatedStringResource(R.string.communityJoin),
        textStyle = LocalType.current.xl,
        iconRes = R.drawable.ic_globe,
        iconSize = LocalDimensions.current.iconMedium,
        minHeight = itemHeight,
        modifier = Modifier.qaTag(R.string.AccessibilityId_communityJoin),
        onClick = {
            navigateTo(StartConversationDestination.JoinCommunity)
        }
    )
    Divider(
        paddingValues = PaddingValues(
            start = dividerIndent,
            end = LocalDimensions.current.smallSpacing
        )
    )
    ItemButton(
        text = annotatedStringResource(R.string.sessionInviteAFriend),
        textStyle = LocalType.current.xl,
        iconRes = R.drawable.ic_user_round_plus,
        iconSize = LocalDimensions.current.iconMedium,
        minHeight = itemHeight,
        modifier = Modifier.qaTag(R.string.AccessibilityId_sessionInviteAFriendButton),
        onClick = {
            navigateTo(StartConversationDestination.InviteFriend)
        }
    )

}

@Preview
@Composable
private fun PreviewStartConversationScreen(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        StartConversationScreen(
            accountId = "059287129387123",
            onClose = {},
            navigateTo = {}
        )
    }
}
