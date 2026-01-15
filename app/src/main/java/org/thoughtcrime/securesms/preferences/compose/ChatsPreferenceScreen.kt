package org.thoughtcrime.securesms.preferences.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.IconActionRowItem
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsPreferenceScreen(
    viewModel: ChatsPreferenceViewModel,
    onBlockedContactsClicked: () -> Unit,
    onBackPressed : () -> Unit
) {

    val uiState = viewModel.uiState.collectAsState().value
    ConversationsPreference(
        uiState = uiState,
        sendCommand = viewModel::onCommand,
        onBlockedContactsClicked = onBlockedContactsClicked,
        onBackPressed = onBackPressed
    )
}

@Composable
private fun ConversationsPreference(
    uiState: ChatsPreferenceViewModel.UIState,
    sendCommand: (commands: ChatsPreferenceViewModel.Commands) -> Unit,
    onBlockedContactsClicked: () -> Unit,
    onBackPressed: () -> Unit
) {

    BasePreferenceScreens(
        onBack = onBackPressed,
        title = GetString(R.string.sessionConversations).string()
    ) {
        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsMessageTrimming).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.conversationsMessageTrimmingTrimCommunities),
                    subtitle = annotatedStringResource(R.string.conversationsMessageTrimmingTrimCommunitiesDescription),
                    checked = uiState.trimThreads,
                    qaTag = R.string.qa_preferences_trim_threads,
                    onCheckedChange = { sendCommand(ChatsPreferenceViewModel.Commands.ToggleTrimThreads) }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsSendWithEnterKey).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.conversationsSendWithEnterKey),
                    subtitle = annotatedStringResource(R.string.conversationsSendWithEnterKeyDescription),
                    checked = uiState.sendWithEnter,
                    qaTag = R.string.qa_preferences_send_with_enter,
                    onCheckedChange = { sendCommand(ChatsPreferenceViewModel.Commands.ToggleSendWithEnter) }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsAudioMessages).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.conversationsAutoplayAudioMessage),
                    subtitle = annotatedStringResource(R.string.conversationsAutoplayAudioMessageDescription),
                    checked = uiState.autoplayAudioMessage,
                    qaTag = R.string.qa_preferences_autoplay_audio,
                    onCheckedChange = { sendCommand(ChatsPreferenceViewModel.Commands.ToggleAutoplayAudioMessages) }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.conversationsBlockedContacts).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconActionRowItem(
                    title = annotatedStringResource(R.string.conversationsBlockedContacts),
                    subtitle = annotatedStringResource(R.string.blockedContactsManageDescription),
                    icon = R.drawable.ic_chevron_right,
                    iconSize = LocalDimensions.current.iconSmall,
                    qaTag = R.string.qa_preferences_option_blocked_contacts,
                    onClick = onBlockedContactsClicked
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewChatPreferenceScreen() {
    ConversationsPreference(
        uiState = ChatsPreferenceViewModel.UIState(
            trimThreads = false,
            sendWithEnter = true,
            autoplayAudioMessage = true
        ),
        sendCommand = {},
        onBlockedContactsClicked = {},
        onBackPressed = {}
    )
}