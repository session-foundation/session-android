package org.thoughtcrime.securesms.preferences.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@Composable
fun PrivacySettingsPreferenceScreen(
    viewModel: PrivacySettingsPreferenceViewModel
) {

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyguardSecure()
    }
}

@Composable
fun PrivacySettingsPreference() {
    BasePreferenceScreens(
        onBack = {},
        title = GetString(R.string.sessionPrivacy).string()
    ) {
        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.callsVoiceAndVideoBeta).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.callsVoiceAndVideo),
                    subtitle = annotatedStringResource(R.string.callsVoiceAndVideoToggleDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.screenSecurity).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.lockApp),
                    subtitle = annotatedStringResource(R.string.lockAppDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.sessionMessageRequests).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.messageRequestsCommunities),
                    subtitle = annotatedStringResource(R.string.messageRequestsCommunitiesDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.readReceipts).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.readReceipts),
                    subtitle = annotatedStringResource(R.string.readReceiptsDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.typingIndicators).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.typingIndicators),
                    subtitle = annotatedStringResource(R.string.typingIndicatorsDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.linkPreviews).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.linkPreviewsSend),
                    subtitle = annotatedStringResource(R.string.linkPreviewsDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        CategoryCell(
            modifier = Modifier,
            title = GetString(R.string.incognitoKeyboard).string()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchActionRowItem(
                    title = annotatedStringResource(R.string.incognitoKeyboard),
                    subtitle = annotatedStringResource(R.string.incognitoKeyboardDescription),
                    checked = false,
                    qaTag = R.string.qa_pro_settings_action_show_badge,
                    onCheckedChange = { }
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewPrivacySettingsPreference() {
    PrivacySettingsPreference()
}
