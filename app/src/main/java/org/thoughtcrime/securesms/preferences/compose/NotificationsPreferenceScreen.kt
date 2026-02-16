package org.thoughtcrime.securesms.preferences.compose

import android.R.attr.onClick
import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.preferences.compose.NotificationsPreferenceViewModel.Commands.*
import org.thoughtcrime.securesms.preferences.compose.NotificationsPreferenceViewModel.NotificationPreferenceEvent.*
import org.thoughtcrime.securesms.preferences.compose.NotificationsPreferenceViewModel.NotificationPrivacyOption
import org.thoughtcrime.securesms.ui.ActionRowItem
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.IconTextActionRowItem
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.openBatteryOptimizationSettings
import org.thoughtcrime.securesms.ui.requestDozeWhitelist
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType

@Composable
fun NotificationsPreferenceScreen(
    viewModel: NotificationsPreferenceViewModel,
    onBackPressed: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState().value
    val notificationPrivacyOptions = viewModel.privacyOptions

    val context = LocalContext.current

    // Listener for ringtone
    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java
                )
            } else {
                result.data?.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI
                )
            }
            viewModel.onCommand(SetRingtone(uri))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is NavigateToActivity -> {
                    context.startActivity(event.intent)
                }

                is NavigateToBatteryOptimizationSettings -> {
                    context.openBatteryOptimizationSettings()
                }

                is NavigateToSystemBgWhitelist -> {
                    context.requestDozeWhitelist()
                }

                is StartRingtoneActivityForResult -> {
                    ringtonePickerLauncher.launch(event.intent)
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshDozeWhitelist()
    }


    NotificationsPreference(
        uiState = uiState, sendCommand = viewModel::onCommand,
        onBackPressed = onBackPressed,
        privacyOptions = notificationPrivacyOptions
    )
}

@Composable
fun NotificationsPreference(
    uiState: NotificationsPreferenceViewModel.UIState,
    privacyOptions: List<NotificationPrivacyOption> = emptyList(),
    sendCommand: (commands: NotificationsPreferenceViewModel.Commands) -> Unit,
    onBackPressed: () -> Unit
) {

    val fastModeDescription = when (BuildConfig.FLAVOR) {
        "huawei" -> GetString(R.string.notificationsFastModeDescriptionHuawei).string()
        else -> GetString(R.string.notificationsFastModeDescription).string()
    }

    BasePreferenceScreens(
        onBack = { onBackPressed() },
        title = GetString(R.string.sessionNotifications).string()
    ) {
        item {
            CategoryCell(
                modifier = Modifier,
                title = GetString(R.string.notificationsStrategy).string()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SwitchActionRowItem(
                        title = annotatedStringResource(R.string.useFastMode),
                        subtitle = annotatedStringResource(fastModeDescription),
                        subtitleStyle = LocalType.current.large,
                        checked = uiState.isPushEnabled,
                        qaTag = R.string.qa_preferences_enable_push,
                        switchQaTag = R.string.qa_preferences_enable_push_toggle,
                        onCheckedChange = {isEnabled -> sendCommand(TogglePushEnabled(isEnabled)) }
                    )

                    Divider()

                    SwitchActionRowItem(
                        title = annotatedStringResource(R.string.runAppBackground),
                        checked = uiState.isWhitelistedFromDoze,
                        qaTag = R.string.qa_preferences_whitelist,
                        switchQaTag = R.string.qa_preferences_whitelist_toggle,
                        onCheckedChange = { sendCommand(WhiteListClicked) }
                    )

                    Divider()

                    ActionRowItem(
                        title = annotatedStringResource(R.string.notificationsGoToDevice),
                        qaTag = R.string.qa_preferences_navigate_device_settings,
                        onClick = { sendCommand(OpenSystemNotificationSettings) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
            CategoryCell(
                modifier = Modifier,
                title = GetString(R.string.notificationsStyle).string()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconTextActionRowItem(
                        title = annotatedStringResource(R.string.notificationsSound),
                        qaTag = R.string.qa_preferences_ringtone,
                        icon = R.drawable.ic_baseline_arrow_drop_down_24,
                        endText = annotatedStringResource(uiState.ringtone.toString()),
                        onClick = { sendCommand(RingtoneClicked) }
                    )

                    Divider()

                    SwitchActionRowItem(
                        title = annotatedStringResource(R.string.notificationsSoundDescription),
                        checked = uiState.soundWhenAppIsOpen,
                        qaTag = R.string.qa_preferences_sound_when_app_is_open,
                        switchQaTag = R.string.qa_preferences_sound_when_app_is_open_toggle,
                        onCheckedChange = {isEnabled -> sendCommand(ToggleSoundWhenOpen(isEnabled)) }
                    )

                    Divider()

                    SwitchActionRowItem(
                        title = annotatedStringResource(R.string.notificationsVibrate),
                        checked = uiState.vibrate,
                        qaTag = R.string.qa_preferences_vibrate,
                        switchQaTag = R.string.qa_preferences_vibrate_toggle,
                        onCheckedChange = {isEnabled -> sendCommand(ToggleVibrate(isEnabled)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
            CategoryCell(
                modifier = Modifier,
                title = GetString(R.string.notificationsContent).string()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {

                    IconTextActionRowItem(
                        title = annotatedStringResource(R.string.notificationsContent),
                        subtitle = annotatedStringResource(R.string.notificationsContentDescription),
                        subtitleStyle = LocalType.current.large,
                        qaTag = R.string.qa_preferences_option_notification_privacy,
                        icon = R.drawable.ic_baseline_arrow_drop_down_24,
                        endText = annotatedStringResource(uiState.notificationPrivacy.toString()),
                        onClick = { sendCommand(ShowNotificationPrivacyDialog) }
                    )
                }
            }
        }
    }


    if (uiState.showWhitelistEnableDialog) {
        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideWhitelistEnableDialog)
            },
            title = Phrase.from(LocalContext.current, R.string.runSessionBackground)
                .put(APP_NAME_KEY, stringResource(R.string.app_name))
                .format().toString(),
            text = Phrase.from(LocalContext.current, R.string.runSessionBackgroundDescription)
                .put(APP_NAME_KEY, stringResource(R.string.app_name))
                .format().toString(),
            buttons = listOf(
                DialogButtonData(
                    text = GetString(R.string.allow),
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_whitelist_confirm),
                    onClick = {
                        sendCommand(OpenSystemBgWhitelist)
                    }
                ),
                DialogButtonData(
                    text = GetString(R.string.cancel),
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_whitelist_cancel),
                    onClick = {
                        sendCommand(HideWhitelistEnableDialog)
                    }
                ),
            )
        )
    }

    if (uiState.showWhitelistDisableDialog) {
        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideWhitelistDisableDialog)
            },
            title = stringResource(R.string.limitBackgroundActivity),
            text = Phrase.from(
                LocalContext.current,
                R.string.limitBackgroundActivityDescription
            )
                .put(APP_NAME_KEY, stringResource(R.string.app_name))
                .format().toString(),
            buttons = listOf(
                DialogButtonData(
                    text = GetString("Change Setting"),
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_whitelist_confirm),
                    color = LocalColors.current.danger,
                    onClick = {
                        // we can't disable it ourselves, but we can take the user to the right settings instead
                        sendCommand(OpenBatteryOptimizationSettings)
                    }
                ),
                DialogButtonData(
                    text = GetString(R.string.cancel),
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_whitelist_cancel),
                    onClick = { sendCommand(HideWhitelistDisableDialog) }
                ),
            )
        )
    }

    if (uiState.showNotificationPrivacyDialog) {
        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideNotificationPrivacyDialog)
            },
            title = stringResource(R.string.notificationsContent),
            content = {
                privacyOptions.forEachIndexed { index, option ->
                    DialogTitledRadioButton(
                        option = RadioOption(
                            value = Unit,
                            title = GetString(option.label),
                            qaTag = GetString(stringResource(R.string.qa_preferences_option_notification_privacy) + "-${option.value}"),
                            selected = option.label == uiState.notificationPrivacy
                        )
                    ) {
                        sendCommand(SelectNotificationPrivacyOption(option.value))
                    }

                }
            },
            buttons = listOf(
                DialogButtonData(
                    text = GetString(stringResource(R.string.cancel)),
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_whitelist_cancel),
                ),
            )
        )
    }
}

@Preview
@Composable
fun PreviewNotificationsPreferenceScreen() {
    NotificationsPreference(
        uiState = NotificationsPreferenceViewModel.UIState(),
        onBackPressed = {},
        sendCommand = {}
    )
}