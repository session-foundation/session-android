package org.thoughtcrime.securesms.preferences.compose

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.squareup.phrase.Phrase
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants.SESSION_FOUNDATION
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SESSION_FOUNDATION_KEY
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel.Commands.*
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.util.IntentUtils

@Composable
fun PrivacySettingsPreferenceScreen(
    viewModel: PrivacySettingsPreferenceViewModel
) {

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyguardSecure()
    }

    val uiState = viewModel.uiState.collectAsState().value

    PrivacySettingsPreference(
        uiState = uiState,
        sendCommand = viewModel::onCommand
    )
}

@Composable
fun PrivacySettingsPreference(
    uiState: PrivacySettingsPreferenceViewModel.UIState,
    sendCommand: (command: PrivacySettingsPreferenceViewModel.Commands) -> Unit
) {

    val context = LocalContext.current

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
                    checked = uiState.callNotificationsEnabled,
                    qaTag = R.string.qa_preferences_voice_calls,
                    onCheckedChange = { sendCommand(ToggleCallsNotification) }
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
                    subtitle = annotatedStringResource(
                        Phrase.from(context, R.string.lockAppDescription)
                            .put(APP_NAME_KEY, R.string.app_name)
                            .format()
                    ),
                    checked = uiState.screenLockChecked,
                    qaTag = R.string.qa_preferences_lock_app,
                    onCheckedChange = { sendCommand(ToggleLockApp) }
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
                    checked = uiState.allowCommunityMessageRequests,
                    qaTag = R.string.qa_preferences_message_requests,
                    onCheckedChange = { sendCommand(ToggleCommunityRequests) }
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
                    checked = uiState.readReceiptsEnabled,
                    qaTag = R.string.qa_preferences_read_receipt,
                    onCheckedChange = { sendCommand(ToggleReadReceipts) }
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
                    checked = uiState.typingIndicators,
                    qaTag = R.string.qa_preferences_typing_indicator,
                    onCheckedChange = { sendCommand(ToggleTypingIndicators) }
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
                    checked = uiState.linkPreviewEnabled,
                    qaTag = R.string.qa_preferences_link_previews,
                    onCheckedChange = { sendCommand(ToggleLinkPreviews) }
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
                    checked = uiState.incognitoKeyboardEnabled,
                    qaTag = R.string.qa_preferences_incognito_keyboard,
                    onCheckedChange = { sendCommand(ToggleIncognitoKeyboard) }
                )
            }
        }
    }

    if (uiState.showCallsWarningDialog) {
        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideCallsWarningDialog)
            },
            title = stringResource(R.string.callsVoiceAndVideoBeta),
            text = Phrase.from(context, R.string.callsVoiceAndVideoModalDescription)
                .put(SESSION_FOUNDATION_KEY, SESSION_FOUNDATION)
                .format().toString(),
            buttons = listOf(
                DialogButtonData(
                    text = GetString(stringResource(R.string.enable)),
                    qaTag = stringResource(R.string.qa_preferences_dialog_cancel),
                    onClick = {
                        sendCommand(EnableCalls)
                    }
                ),
                DialogButtonData(
                    text = GetString(stringResource(R.string.cancel)),
                    qaTag = stringResource(R.string.qa_preferences_dialog_cancel),
                    onClick = {
                        sendCommand(HideCallsWarningDialog)
                    }
                ),
            )
        )
    }

    if (uiState.showCallsNotificationDialog) {
        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideCallsNotificationDialog)
            },
            title = stringResource(R.string.sessionNotifications),
            text = stringResource(R.string.callsNotificationsRequired),
            buttons = listOf(
                DialogButtonData(
                    text = GetString(stringResource(R.string.enable)),
                    qaTag = stringResource(R.string.qa_preferences_dialog_cancel),
                    onClick = {
//                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
//                            .putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
//                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                            .takeIf { IntentUtils.isResolvable(requireContext(), it) }
//                            ?.let { startActivity(it) }
                    }
                ),
                DialogButtonData(
                    text = GetString(stringResource(R.string.cancel)),
                    qaTag = stringResource(R.string.qa_preferences_dialog_cancel),
                    onClick = {
                        sendCommand(HideCallsNotificationDialog)
                    }
                ),
            )
        )
    }

}

@Preview
@Composable
fun PreviewPrivacySettingsPreference() {
    PrivacySettingsPreference(
        uiState = PrivacySettingsPreferenceViewModel.UIState(),
        sendCommand = {}
    )
}
