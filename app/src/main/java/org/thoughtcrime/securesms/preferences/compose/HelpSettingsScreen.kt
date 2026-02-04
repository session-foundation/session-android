package org.thoughtcrime.securesms.preferences.compose

import android.Manifest
import android.os.Build
import android.widget.Toast
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
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.IconActionRowItem
import org.thoughtcrime.securesms.ui.components.ExportLogsDialog
import org.thoughtcrime.securesms.ui.components.LogExporter
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.findActivity
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.ui.openUrl
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

private const val CROWDIN_URL = "https://getsession.org/translate"
private const val FEEDBACK_URL = "https://getsession.org/survey"
private const val FAQ_URL = "https://getsession.org/faq"
private const val SUPPORT_URL = "https://sessionapp.zendesk.com/hc/en-us"

@Composable
fun HelpSettingsScreen(
    viewModel: HelpSettingsViewModel,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                HelpSettingsViewModel.HelpSettingsEvent.HandleExportLogs -> {
                    // Ask for permissions first
                    Permissions.with(context.findActivity())
                        .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        .maxSdkVersion(Build.VERSION_CODES.P)
                        .withPermanentDenialDialog(
                            context.getSubbedString(
                                R.string.permissionsStorageDeniedLegacy,
                                APP_NAME_KEY to context.applicationContext.getString(R.string.app_name)
                            )
                        )
                        .onAnyDenied {
                            val txt = context.getSubbedString(
                                R.string.permissionsStorageDeniedLegacy,
                                APP_NAME_KEY to context.applicationContext.getString(R.string.app_name)
                            )
                            Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
                        }
                        .onAllGranted {
                            // show dialog
                            viewModel.onCommand(HelpSettingsViewModel.Commands.ShowExportDialog)
                        }
                        .execute()
                }

                is HelpSettingsViewModel.HelpSettingsEvent.HandleUrl -> {
                    context.openUrl(event.urlString)
                }
            }
        }
    }

    val uiState = viewModel.uiState.collectAsState().value

    HelpSettings(
        uiState = uiState,
        sendCommand = viewModel::onCommand,
        exporter = viewModel.exporter,
        onBackPressed = onBackPressed
    )
}

@Composable
fun HelpSettings(
    uiState: HelpSettingsViewModel.UIState,
    sendCommand: (HelpSettingsViewModel.Commands) -> Unit,
    exporter: LogExporter?,
    onBackPressed: () -> Unit
) {

    val context = LocalContext.current

    BasePreferenceScreens(
        onBack = onBackPressed,
        title = GetString(R.string.sessionConversations).string()
    ) {
        item {
            CategoryCell(
                modifier = Modifier,
            ) {
                IconActionRowItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = annotatedStringResource(R.string.helpReportABug),
                    subtitle = annotatedStringResource(
                        Phrase.from(context, R.string.helpReportABugExportLogsDescription)
                            .put(APP_NAME_KEY, stringResource(R.string.app_name))
                            .format()
                    ),
                    icon = R.drawable.ic_square_arrow_up_right,
                    iconSize = LocalDimensions.current.iconSmall,
                    qaTag = R.string.qa_help_settings_export,
                    onClick = { sendCommand(HelpSettingsViewModel.Commands.ExportLogs) }
                )
            }
            
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
            CategoryCell(
                modifier = Modifier,
            ) {
                IconActionRowItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = annotatedStringResource(
                        Phrase.from(context, R.string.helpHelpUsTranslateSession)
                            .put(APP_NAME_KEY, stringResource(R.string.app_name))
                            .format()
                    ),
                    icon = R.drawable.ic_square_arrow_up_right,
                    iconSize = LocalDimensions.current.iconSmall,
                    qaTag = R.string.qa_help_settings_translate,
                    onClick = { sendCommand(HelpSettingsViewModel.Commands.OpenUrl(CROWDIN_URL)) }
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
            CategoryCell(
                modifier = Modifier,
            ) {
                IconActionRowItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = annotatedStringResource(R.string.helpWedLoveYourFeedback),
                    subtitle = annotatedStringResource(R.string.blockedContactsManageDescription),
                    icon = R.drawable.ic_square_arrow_up_right,
                    iconSize = LocalDimensions.current.iconSmall,
                    qaTag = R.string.qa_help_settings_feedback,
                    onClick = { sendCommand(HelpSettingsViewModel.Commands.OpenUrl(FEEDBACK_URL)) }
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }

        item {
            CategoryCell(
                modifier = Modifier,
            ) {
                IconActionRowItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = annotatedStringResource(R.string.helpFAQ),
                    icon = R.drawable.ic_square_arrow_up_right,
                    iconSize = LocalDimensions.current.iconSmall,
                    qaTag = R.string.qa_help_settings_faq,
                    onClick = { sendCommand(HelpSettingsViewModel.Commands.OpenUrl(FAQ_URL)) }
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }


        item {
            CategoryCell(
                modifier = Modifier,
            ) {
                IconActionRowItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = annotatedStringResource(R.string.helpSupport),
                    icon = R.drawable.ic_square_arrow_up_right,
                    iconSize = LocalDimensions.current.iconSmall,
                    qaTag = R.string.qa_help_settings_support,
                    onClick = { sendCommand(HelpSettingsViewModel.Commands.OpenUrl(SUPPORT_URL)) }
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        }
    }

    if (uiState.showExportDialog) {
        ExportLogsDialog(
            logExporter = exporter!!,
            onDismissRequest = {
                sendCommand(HelpSettingsViewModel.Commands.HideExportDialog)
            }
        )
    }
}

@Preview
@Composable
fun PreviewHelpSettingsScreen() {
    HelpSettings(
        uiState = HelpSettingsViewModel.UIState(),
        sendCommand = { },
        exporter = null,
        onBackPressed = {}
    )
}