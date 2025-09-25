package org.thoughtcrime.securesms.debugmenu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ChangeEnvironment
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ClearTrustedDownloads
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.Copy07PrefixedBlindedPublicKey
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.CopyAccountId
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.HideDeprecationChangeDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.HideEnvironmentWarningDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.OverrideDeprecationState
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ScheduleTokenNotification
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ShowDeprecationChangeDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.ShowEnvironmentWarningDialog
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Commands.GenerateContacts
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.Button
import org.thoughtcrime.securesms.ui.components.ButtonType
import org.thoughtcrime.securesms.ui.components.DropDown
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.SessionSwitch
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
                    DialogButtonData(
                        text = GetString(R.string.cancel),
                        onClick = { sendCommand(HideDeprecationChangeDialog) }
                    ),
                    DialogButtonData(
                        text = GetString(android.R.string.ok),
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
                    DialogButtonData(
                        text = GetString(R.string.cancel),
                        onClick = { sendCommand(HideEnvironmentWarningDialog) }
                    ),
                    DialogButtonData(
                        text = GetString(android.R.string.ok),
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

            if (uiState.dbInspectorState != DebugMenuViewModel.DatabaseInspectorState.NOT_AVAILABLE) {
                DebugCell("Database inspector") {
                    Button(
                        onClick = {
                            sendCommand(DebugMenuViewModel.Commands.ToggleDatabaseInspector)
                        },
                        text = if (uiState.dbInspectorState == DebugMenuViewModel.DatabaseInspectorState.STOPPED)
                            "Start"
                        else "Stop",
                        type = ButtonType.AccentFill,
                    )
                }
            }

            // Session Pro
            DebugCell(
                "Session Pro",
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

                Text(text = "Purchase a plan")
                Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

                DropDown(
                    selected = null,
                    modifier = modifier,
                    values = uiState.debugProPlans,
                    onValueSelected = { sendCommand(DebugMenuViewModel.Commands.PurchaseDebugPlan(it)) },
                    labeler = { it?.label ?: "Select a plan to buy" }
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

                DebugSwitchRow(
                    text = "Set current user as Pro",
                    checked = uiState.forceCurrentUserAsPro,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.ForceCurrentUserAsPro(it))
                    }
                )

                AnimatedVisibility(uiState.forceCurrentUserAsPro) {
                    Column {
                        Text(
                            modifier = Modifier.padding(top = LocalDimensions.current.xxsSpacing),
                            text = "Debug Subscription Status",
                            style = LocalType.current.base
                        )
                        DropDown(
                            modifier = Modifier.fillMaxWidth()
                                .padding(top = LocalDimensions.current.xxsSpacing),
                            selectedText = uiState.selectedDebugSubscriptionStatus.label,
                            values = uiState.debugSubscriptionStatuses.map { it.label },
                            onValueSelected = { selection ->
                                sendCommand(
                                    DebugMenuViewModel.Commands.SetDebugSubscriptionStatus(
                                        uiState.debugSubscriptionStatuses.first { it.label == selection }
                                    )
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
                DebugSwitchRow(
                    text = "Set all incoming messages as Pro",
                    checked = uiState.forceIncomingMessagesAsPro,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.ForceIncomingMessagesAsPro(it))
                    }
                )

                AnimatedVisibility(uiState.forceIncomingMessagesAsPro) {
                    Column{
                        DebugCheckboxRow(
                            text = "Message Feature: Pro Badge",
                            minHeight = 30.dp,
                            checked = uiState.messageProFeature.contains(ProStatusManager.MessageProFeature.ProBadge),
                            onCheckedChange = {
                                sendCommand(
                                    DebugMenuViewModel.Commands.SetMessageProFeature(
                                        ProStatusManager.MessageProFeature.ProBadge, it
                                    )
                                )
                            }
                        )

                        DebugCheckboxRow(
                            text = "Message Feature: Long Message",
                            minHeight = 30.dp,
                            checked = uiState.messageProFeature.contains(ProStatusManager.MessageProFeature.LongMessage),
                            onCheckedChange = {
                                sendCommand(
                                    DebugMenuViewModel.Commands.SetMessageProFeature(
                                        ProStatusManager.MessageProFeature.LongMessage, it
                                    )
                                )
                            }
                        )

                        DebugCheckboxRow(
                            text = "Message Feature: Animated Avatar",
                            minHeight = 30.dp,
                            checked = uiState.messageProFeature.contains(ProStatusManager.MessageProFeature.AnimatedAvatar),
                            onCheckedChange = {
                                sendCommand(
                                    DebugMenuViewModel.Commands.SetMessageProFeature(
                                        ProStatusManager.MessageProFeature.AnimatedAvatar, it
                                    )
                                )
                            }
                        )
                    }

                }

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
                DebugSwitchRow(
                    text = "Set app as post Pro launch",
                    checked = uiState.forcePostPro,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.ForcePostPro(it))
                    }
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
                DebugSwitchRow(
                    text = "Set other users as Pro",
                    checked = uiState.forceOtherUsersAsPro,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.ForceOtherUsersAsPro(it))
                    }
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
                DebugSwitchRow(
                    text = "Force 30sec TTL avatar",
                    checked = uiState.forceShortTTl,
                    onCheckedChange = {
                        sendCommand(DebugMenuViewModel.Commands.ForceShortTTl(it))
                    }
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
                ){
                    Image(
                        modifier = Modifier.size(LocalDimensions.current.iconXSmall),
                        painter = painterResource(id = R.drawable.ic_triangle_alert),
                        colorFilter = ColorFilter.tint(LocalColors.current.warning),
                        contentDescription = null,
                    )

                    Text(
                        text = "For avatar animation or Pro badge changes based on the values modified above, please restart the app",
                        style = LocalType.current.base.copy(color = LocalColors.current.warning)
                    )
                }
            }

            // Fake contacts
            DebugCell("Generate fake contacts") {
                var prefix by remember { mutableStateOf("User-") }
                var count by remember { mutableStateOf("2000") }

                DebugRow("Prefix") {
                    SessionOutlinedTextField(
                        text = prefix,
                        innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                        onChange = { prefix = it },
                        modifier = Modifier.weight(2f)
                    )
                }

                DebugRow("Count") {
                    SessionOutlinedTextField(
                        text = count,
                        innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                        onChange = { value -> count = value.filter { it.isDigit() } },
                        modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                SlimOutlineButton(modifier = Modifier.fillMaxWidth(), text = "Generate") {
                    sendCommand(
                        GenerateContacts(
                            prefix = prefix,
                            count = count.toInt(),
                        )
                    )
                }
            }

            // Session Token
            DebugCell("Session Token") {
                // Schedule a test token-drop notification for 10 seconds from now
                SlimOutlineButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Schedule Token Page Notification (10s)",
                    onClick = { sendCommand(ScheduleTokenNotification) }
                )
            }

            // Keys
            DebugCell("User Details") {

                SlimOutlineButton (
                    text = "Copy Account ID",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        sendCommand(CopyAccountId)
                    }
                )

                SlimOutlineButton(
                    text = "Copy 07-prefixed Version Blinded Public Key",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        sendCommand(Copy07PrefixedBlindedPublicKey)
                    }
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

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
                    modifier = Modifier.fillMaxWidth(),
                    text = "Clear All Trusted Downloads",
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
                    DialogButtonData(
                        text = GetString(R.string.cancel),
                        onClick = {
                            showingDeprecatedTimePicker = false
                            showingDeprecatingStartTimePicker = false
                        }
                    ),
                    DialogButtonData(
                        text = GetString(android.R.string.ok),
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
    minHeight: Dp = LocalDimensions.current.itemButtonIconSpacing,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.heightIn(min = minHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
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

@Composable
fun DebugCheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = LocalDimensions.current.itemButtonIconSpacing,
) {
    DebugRow(
        title = text,
        minHeight = minHeight,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = LocalColors.current.accent,
                uncheckedColor = LocalColors.current.disabled,
                checkmarkColor = LocalColors.current.background
            )
        )
    }

}

@Composable
fun ColumnScope.DebugCell(
    title: String,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(LocalDimensions.current.xsSpacing),
    content: @Composable ColumnScope.() -> Unit
) {
    Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

    Cell(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(LocalDimensions.current.spacing),
            verticalArrangement = verticalArrangement
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
                deprecatingStartTime = ZonedDateTime.now(),
                forceCurrentUserAsPro = true,
                forceIncomingMessagesAsPro = true,
                forceOtherUsersAsPro = false,
                forcePostPro = false,
                forceShortTTl = false,
                messageProFeature = setOf(ProStatusManager.MessageProFeature.AnimatedAvatar),
                dbInspectorState = DebugMenuViewModel.DatabaseInspectorState.STARTED,
                debugSubscriptionStatuses = setOf(DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE),
                selectedDebugSubscriptionStatus = DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE,
                debugProPlans = emptyList(),
            ),
            sendCommand = {},
            onClose = {}
        )
    }
}