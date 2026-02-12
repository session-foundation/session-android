package org.thoughtcrime.securesms.preferences.compose

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.squareup.phrase.Phrase
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants.SESSION_FOUNDATION
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SESSION_FOUNDATION_KEY
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel.Commands.*
import org.thoughtcrime.securesms.preferences.compose.PrivacySettingsPreferenceViewModel.PrivacySettingsPreferenceEvent.*
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.TypingIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.findActivity
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.util.IntentUtils

@Composable
fun PrivacySettingsPreferenceScreen(
    viewModel: PrivacySettingsPreferenceViewModel,
    onBackPressed: () -> Unit
) {

    val context = LocalContext.current
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyguardSecure()
        viewModel.checkScrollActions()
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is OpenAppNotificationSettings -> {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .takeIf { IntentUtils.isResolvable(context, it) }
                        ?.let { context.startActivity(it, null) }
                }

                AskMicrophonePermission -> {
                    // Ask for permissions here
                    Permissions.with(context.findActivity())
                        .request(Manifest.permission.RECORD_AUDIO)
                        .onAllGranted {
                            viewModel.onCommand(ToggleCallsNotification(true))
                        }
                        .withPermanentDenialDialog(
                            context.getSubbedString(
                                R.string.permissionsMicrophoneAccessRequired,
                                APP_NAME_KEY to context.applicationContext.getString(R.string.app_name)
                            )
                        )
                        .onAnyDenied {
                            viewModel.onCommand(ToggleCallsNotification(false))
                        }
                        .execute()
                }

                StartLockToggledService -> {
                    val intent = Intent(context, KeyCachingService::class.java)
                    intent.action = KeyCachingService.LOCK_TOGGLED_EVENT
                    context.startService(intent)
                }

                is ScrollToIndex -> {
                    listState.animateScrollToItem(event.index)
                }
            }
        }
    }

    val uiState = viewModel.uiState.collectAsState().value

    PrivacySettingsPreference(
        uiState = uiState,
        sendCommand = viewModel::onCommand,
        onBackPressed = onBackPressed,
        listState = listState
    )
}

@Composable
fun PrivacySettingsPreference(
    uiState: PrivacySettingsPreferenceViewModel.UIState,
    listState: LazyListState,
    sendCommand: (command: PrivacySettingsPreferenceViewModel.Commands) -> Unit,
    onBackPressed: () -> Unit
) {

    val context = LocalContext.current

    BasePreferenceScreens(
        onBack = { onBackPressed() },
        title = GetString(R.string.sessionPrivacy).string(),
        listState = listState
    ) {
        item {
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
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                sendCommand(ShowCallsWarningDialog)
                            } else {
                                sendCommand(ToggleCallsNotification(false))
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }
        item {
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
                                .put(APP_NAME_KEY, stringResource(R.string.app_name))
                                .format()
                        ),
                        enabled = uiState.screenLockEnabled,
                        checked = uiState.screenLockChecked,
                        qaTag = R.string.qa_preferences_lock_app,
                        onCheckedChange = { isEnabled -> sendCommand(ToggleLockApp(isEnabled)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
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
        }

        item {
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
                        onCheckedChange = { isEnabled -> sendCommand(ToggleReadReceipts(isEnabled)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
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
                        switchLeadingContent = {
                            TypingIndicator(isTyping = true)
                        },
                        onCheckedChange = { isEnabled ->
                            sendCommand(
                                ToggleTypingIndicators(
                                    isEnabled
                                )
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
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
                        onCheckedChange = { isEnabled -> sendCommand(ToggleLinkPreviews(isEnabled)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
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
                        onCheckedChange = { isEnabled ->
                            sendCommand(
                                ToggleIncognitoKeyboard(
                                    isEnabled
                                )
                            )
                        }
                    )
                }
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
                    qaTag = stringResource(R.string.qa_preferences_dialog_enable),
                    onClick = {
                        sendCommand(AskMicPermission)
                    }
                ),
                DialogButtonData(
                    text = GetString(stringResource(R.string.cancel)),
                    qaTag = stringResource(R.string.qa_preferences_dialog_cancel),
                    onClick = {
                        sendCommand(HideCallsWarningDialog)
                        sendCommand(ToggleCallsNotification(false))
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
                        sendCommand(NavigateToAppNotificationsSettings)
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
        sendCommand = {},
        onBackPressed = {},
        listState = LazyListState()
    )
}
