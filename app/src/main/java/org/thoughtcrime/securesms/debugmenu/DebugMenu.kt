package org.thoughtcrime.securesms.debugmenu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ChangeEnvironment
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ClearTrustedDownloads
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.HideDeprecationChangeDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.HideEnvironmentWarningDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.OverrideDeprecationState
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ShowDeprecationChangeDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ShowEnvironmentWarningDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.GenerateContacts
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogButtonModel
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.DropDown
import org.thoughtcrime.securesms.ui.components.SlimOutlineButton
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.bold
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMenu(
    uiState: DebugMenuViewModel.UIState,
    sendCommand: (DebugMenuViewModel.Commands) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val datePickerState = rememberDatePickerState()
    val timePickerState = rememberTimePickerState()

    var showingDeprecatedDatePicker by remember { mutableStateOf(false) }
    var showingDeprecatedTimePicker by remember { mutableStateOf(false) }

    var showingDeprecatingStartDatePicker by remember { mutableStateOf(false) }
    var showingDeprecatingStartTimePicker by remember { mutableStateOf(false) }

    val getPickedTime = {
        val localDate = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(datePickerState.selectedDateMillis!!), ZoneId.of("UTC")
        ).toLocalDate()

        val localTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
        ZonedDateTime.of(localDate, localTime, ZoneId.systemDefault())
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // App bar
            BackAppBar(title = "Debug Menu", onBack = onClose)
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        // display a snackbar when required
        LaunchedEffect(uiState.snackMessage) {
            if (!uiState.snackMessage.isNullOrEmpty()) {
                snackbarHostState.showSnackbar(uiState.snackMessage)
            }
        }

        // Alert dialogs
        if (uiState.showDeprecatedStateWarningDialog) {
            AlertDialog(
                onDismissRequest = { sendCommand(HideEnvironmentWarningDialog) },
                title = "Are you sure you want to change the deprecation state?",
                text = "This will restart the app...",
                showCloseButton = false, // don't display the 'x' button
                buttons = listOf(
                    DialogButtonModel(
                        text = GetString(R.string.cancel),
                        onClick = { sendCommand(HideDeprecationChangeDialog) }
                    ),
                    DialogButtonModel(
                        text = GetString(R.string.ok),
                        onClick = { sendCommand(OverrideDeprecationState) }
                    )
                )
            )
        }

        if (uiState.showEnvironmentWarningDialog) {
            AlertDialog(
                onDismissRequest = { sendCommand(HideEnvironmentWarningDialog) },
                title = "Are you sure you want to switch environments?",
                text = "Changing this setting will result in all conversations and Snode data being cleared...",
                showCloseButton = false, // don't display the 'x' button
                buttons = listOf(
                    DialogButtonModel(
                        text = GetString(R.string.cancel),
                        onClick = { sendCommand(HideEnvironmentWarningDialog) }
                    ),
                    DialogButtonModel(
                        text = GetString(R.string.ok),
                        onClick = { sendCommand(ChangeEnvironment) }
                    )
                )
            )
        }

        if (uiState.showLoadingDialog) {
            LoadingDialog(title = "Applying changes...")
        }

        Column(
            modifier = Modifier
                .background(LocalColors.current.background)
                .padding(horizontal = LocalDimensions.current.spacing)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(contentPadding.calculateTopPadding()))

            // Info pane
            val clipboardManager = LocalClipboardManager.current
            val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE} - ${
                BuildConfig.GIT_HASH.take(
                    6
                )
            })"

            DebugCell(
                modifier = Modifier.clickable {
                    // clicking the cell copies the version number to the clipboard
                    clipboardManager.setText(AnnotatedString(appVersion))
                },
                title = "App Info"
            ) {
                Text(
                    text = "Version: $appVersion",
                    style = LocalType.current.base
                )
            }

            // Environment
            DebugCell("Environment") {
                DropDown(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    selectedText = uiState.currentEnvironment,
                    values = uiState.environments,
                    onValueSelected = {
                        sendCommand(ShowEnvironmentWarningDialog(it))
                    }
                )
            }

            DebugCell("Generate fake contacts") {
                var prefix by remember { mutableStateOf("User-") }
                var count by remember { mutableStateOf("2000") }

                DebugRow("Prefix") {
                    TextField(
                        value = prefix,
                        onValueChange = { prefix = it },
                        modifier = Modifier.weight(2f)
                    )
                }

                DebugRow("Count") {
                    TextField(
                        value = count,
                        onValueChange = { value -> count = value.filter { it.isDigit() } },
                        modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                SlimOutlineButton("Generate") {
                    sendCommand(
                        GenerateContacts(
                            prefix = prefix,
                            count = count.toInt(),
                        )
                    )
                }
            }

            // Flags
            DebugCell("Flags") {
                DebugSwitchRow(
                    text = "Hide Message Requests",
                    checked = uiState.hideMessageRequests,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.HideMessageRequest(it))
                    }
                )

                DebugSwitchRow(
                    text = "Hide Note to Self",
                    checked = uiState.hideNoteToSelf,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.HideNoteToSelf(it))
                    }
                )

                SlimOutlineButton(
                    "Clear All Trusted Downloads",
                ) {
                    sendCommand(ClearTrustedDownloads)
                }
            }

            // Group deprecation state
            DebugCell("Legacy Group Deprecation Overrides") {
                DropDown(
                    selectedText = uiState.forceDeprecationState.displayName,
                    values = uiState.availableDeprecationState.map { it.displayName },
                ) { selected ->
                    val override = LegacyGroupDeprecationManager.DeprecationState.entries
                        .firstOrNull { it.displayName == selected }

                    sendCommand(ShowDeprecationChangeDialog(override))
                }

                DebugRow(title = "Deprecating start date", modifier = Modifier.clickable {
                    datePickerState.applyFromZonedDateTime(uiState.deprecatingStartTime)
                    timePickerState.applyFromZonedDateTime(uiState.deprecatingStartTime)
                    showingDeprecatingStartDatePicker = true
                }) {
                    Text(
                        text = uiState.deprecatingStartTime.withZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDate().toString()
                    )
                }

                DebugRow(title = "Deprecating start time", modifier = Modifier.clickable {
                    datePickerState.applyFromZonedDateTime(uiState.deprecatingStartTime)
                    timePickerState.applyFromZonedDateTime(uiState.deprecatingStartTime)
                    showingDeprecatingStartTimePicker = true
                }) {
                    Text(
                        text = uiState.deprecatingStartTime.withZoneSameInstant(ZoneId.systemDefault())
                            .toLocalTime().toString()
                    )
                }

                DebugRow(title = "Deprecated date", modifier = Modifier.clickable {
                    datePickerState.applyFromZonedDateTime(uiState.deprecatedTime)
                    timePickerState.applyFromZonedDateTime(uiState.deprecatedTime)
                    showingDeprecatedDatePicker = true
                }) {
                    Text(
                        text = uiState.deprecatedTime.withZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDate().toString()
                    )
                }

                DebugRow(title = "Deprecated time", modifier = Modifier.clickable {
                    datePickerState.applyFromZonedDateTime(uiState.deprecatedTime)
                    timePickerState.applyFromZonedDateTime(uiState.deprecatedTime)
                    showingDeprecatedTimePicker = true
                }) {
                    Text(
                        text = uiState.deprecatedTime.withZoneSameInstant(ZoneId.systemDefault())
                            .toLocalTime().toString()
                    )
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
            Spacer(modifier = Modifier.height(contentPadding.calculateBottomPadding()))
        }

        // Deprecation date picker
        if (showingDeprecatedDatePicker || showingDeprecatingStartDatePicker) {
            DatePickerDialog(
                onDismissRequest = {
                    showingDeprecatedDatePicker = false
                    showingDeprecatingStartDatePicker = false
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (showingDeprecatedDatePicker) {
                            sendCommand(
                                DebugMenuViewModel.Commands.OverrideDeprecatedTime(
                                    getPickedTime()
                                )
                            )
                            showingDeprecatedDatePicker = false
                        } else {
                            sendCommand(
                                DebugMenuViewModel.Commands.OverrideDeprecatingStartTime(
                                    getPickedTime()
                                )
                            )
                            showingDeprecatingStartDatePicker = false
                        }
                    }) {
                        Text("Set", color = LocalColors.current.text)
                    }
                },
            ) {
                DatePicker(datePickerState)
            }
        }

        if (showingDeprecatedTimePicker || showingDeprecatingStartTimePicker) {
            AlertDialog(
                onDismissRequest = {
                    showingDeprecatedTimePicker = false
                    showingDeprecatingStartTimePicker = false
                },
                title = "Set Time",
                buttons = listOf(
                    DialogButtonModel(
                        text = GetString(R.string.cancel),
                        onClick = {
                            showingDeprecatedTimePicker = false
                            showingDeprecatingStartTimePicker = false
                        }
                    ),
                    DialogButtonModel(
                        text = GetString(R.string.ok),
                        onClick = {
                            if (showingDeprecatedTimePicker) {
                                sendCommand(
                                    DebugMenuViewModel.Commands.OverrideDeprecatedTime(
                                        getPickedTime()
                                    )
                                )
                                showingDeprecatedTimePicker = false
                            } else {
                                sendCommand(
                                    DebugMenuViewModel.Commands.OverrideDeprecatingStartTime(
                                        getPickedTime()
                                    )
                                )
                                showingDeprecatingStartTimePicker = false
                            }
                        }
                    )
                )
            ) {
                TimePicker(timePickerState)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun DatePickerState.applyFromZonedDateTime(time: ZonedDateTime) {
    selectedDateMillis = time.withZoneSameInstant(ZoneId.of("UTC")).toEpochSecond() * 1000L
}

@OptIn(ExperimentalMaterial3Api::class)
private fun TimePickerState.applyFromZonedDateTime(time: ZonedDateTime) {
    val normalised = time.withZoneSameInstant(ZoneId.systemDefault())
    hour = normalised.hour
    minute = normalised.minute
}


private val LegacyGroupDeprecationManager.DeprecationState?.displayName: String
    get() {
        return this?.name ?: "No state override"
    }

@Composable
private fun DebugRow(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.heightIn(min = LocalDimensions.current.minItemButtonHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = LocalType.current.base,
            modifier = Modifier.weight(1f)
        )

        content()
    }
}

@Composable
fun DebugSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    DebugRow(
        title = text,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        SessionSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }

}

// todo Get proper styling that works well with ax on all themes and then move this composable in the components file
@Composable
fun SessionSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = LocalColors.current.primary,
            checkedTrackColor = LocalColors.current.background,
            uncheckedThumbColor = LocalColors.current.text,
            uncheckedTrackColor = LocalColors.current.background,
        )
    )
}

@Composable
fun ColumnScope.DebugCell(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

    Cell(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(LocalDimensions.current.spacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
        ) {
            Text(
                text = title,
                style = LocalType.current.large.bold()
            )

            content()
        }
    }
}

@Preview
@Composable
fun PreviewDebugMenu() {
    PreviewTheme {
        DebugMenu(
            uiState = DebugMenuViewModel.UIState(
                currentEnvironment = "Development",
                environments = listOf("Development", "Production"),
                snackMessage = null,
                showEnvironmentWarningDialog = false,
                showLoadingDialog = false,
                showDeprecatedStateWarningDialog = false,
                hideMessageRequests = true,
                hideNoteToSelf = false,
                forceDeprecationState = null,
                deprecatedTime = ZonedDateTime.now(),
                availableDeprecationState = emptyList(),
                deprecatingStartTime = ZonedDateTime.now()
            ),
            sendCommand = {},
            onClose = {}
        )
    }
}