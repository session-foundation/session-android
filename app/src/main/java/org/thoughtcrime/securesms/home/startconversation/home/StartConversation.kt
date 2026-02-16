package org.thoughtcrime.securesms.home.startconversation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val context = LocalContext.current

    Column(modifier = Modifier.background(
        LocalColors.current.backgroundSecondary,
        shape = MaterialTheme.shapes.small.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
    )) {
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                val dividerIndent: Dp = LocalDimensions.current.itemButtonIconSpacing + 2*LocalDimensions.current.smallSpacing
                val newMessageTitleTxt:String = context.resources.getQuantityString(R.plurals.messageNew, 1, 1)
                val itemHeight = 50.dp

                ItemButton(
                    text = annotatedStringResource(newMessageTitleTxt),
                    textStyle = LocalType.current.xl,
                    iconRes = R.drawable.ic_message_square,
                    iconSize = LocalDimensions.current.iconMedium,
                    modifier = Modifier.qaTag(R.string.AccessibilityId_messageNew),
                    minHeight = itemHeight,
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
                    modifier = Modifier.qaTag(R.string.AccessibilityId_groupCreate),
                    minHeight = itemHeight,
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
                    modifier = Modifier.qaTag(R.string.AccessibilityId_communityJoin),
                    minHeight = itemHeight,
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
                    modifier = Modifier.qaTag(R.string.AccessibilityId_sessionInviteAFriendButton),
                    minHeight = itemHeight,
                    onClick = {
                        navigateTo(StartConversationDestination.InviteFriend)
                    }
                )
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
    }
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
