package org.thoughtcrime.securesms.preferences.compose

import android.R.attr.spacing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.IconActionRowItem
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.openUrl
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsPreferenceScreen() {
    BasePreferenceScreens(
        onBack = {},
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
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
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
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
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
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
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
                    qaTag = R.string.AccessibilityId_onboardingTos,
                    onClick = {}
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewChatPreferenceScreen() {
    ConversationsPreferenceScreen()
}