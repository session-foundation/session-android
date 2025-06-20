package org.thoughtcrime.securesms.conversation.v2.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.*
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonModel
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme

@Composable
fun ConversationSettingsDialogs(
    dialogsState: ConversationSettingsViewModel.DialogsState,
    sendCommand: (ConversationSettingsViewModel.Commands) -> Unit
){
    val context = LocalContext.current

    //  Simple dialogs
    if (dialogsState.showSimpleDialog != null) {
        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideSimpleDialog)
            },
            title = annotatedStringResource(dialogsState.showSimpleDialog.title),
            text = annotatedStringResource(dialogsState.showSimpleDialog.message),
            buttons = listOf(
                DialogButtonModel(
                    text = GetString(dialogsState.showSimpleDialog.positiveText),
                    color = if(dialogsState.showSimpleDialog.positiveStyleDanger) LocalColors.current.danger
                    else LocalColors.current.text,
                    onClick = dialogsState.showSimpleDialog.onPositive
                ),
                DialogButtonModel(
                    text = GetString(dialogsState.showSimpleDialog.negativeText),
                    onClick = dialogsState.showSimpleDialog.onNegative
                )
            )
        )
    }

    // Group admin clear messages
    if(dialogsState.groupAdminClearMessagesDialog != null) {
        GroupAdminClearMessagesDialog(
            groupName = dialogsState.groupAdminClearMessagesDialog.groupName,
            sendCommand = sendCommand
        )
    }

    // Nickname
    if(dialogsState.nicknameDialog != null){

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect (Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideNicknameDialog)
            },
            title = AnnotatedString(stringResource(R.string.nicknameSet)),
            text = annotatedStringResource(Phrase.from(context, R.string.nicknameDescription)
                .put(NAME_KEY, dialogsState.nicknameDialog.name)
                .format()),
            showCloseButton = true,
            content = {
                SessionOutlinedTextField(
                    text = dialogsState.nicknameDialog.inputNickname ?: "",
                    modifier = Modifier.qaTag(R.string.qa_conversation_settings_dialog_nickname_input)
                        .focusRequester(focusRequester)
                        .padding(top = LocalDimensions.current.smallSpacing),
                    placeholder = stringResource(R.string.nicknameEnter),
                    innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                    onChange = { updatedText ->
                        sendCommand(UpdateNickname(updatedText))
                    },
                    showClear = true,
                    singleLine = true,
                    onContinue = { sendCommand(SetNickname) },
                    error = dialogsState.nicknameDialog.error,
                )
            },
            buttons = listOf(
                DialogButtonModel(
                    text = GetString(stringResource(id = R.string.save)),
                    enabled = dialogsState.nicknameDialog.setEnabled,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_nickname_set),
                    onClick = { sendCommand(SetNickname) }
                ),
                DialogButtonModel(
                    text = GetString(stringResource(R.string.remove)),
                    color = LocalColors.current.danger,
                    enabled = dialogsState.nicknameDialog.removeEnabled,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_nickname_remove),
                    onClick = {
                        sendCommand(RemoveNickname)
                    }
                )
            )
        )
    }

    // Group Edit
    if(dialogsState.groupEditDialog != null){

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect (Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideGroupEditDialog)
            },
            title = stringResource(R.string.updateGroupInformation),
            text = stringResource(R.string.updateGroupInformationDescription),
            showCloseButton = true,
            content = {
                Column {
                    // group name
                    SessionOutlinedTextField(
                        text = dialogsState.groupEditDialog.inputName ?: "",
                        modifier = Modifier.qaTag(R.string.qa_conversation_settings_dialog_groupname_input)
                            .focusRequester(focusRequester)
                            .padding(top = LocalDimensions.current.smallSpacing),
                        innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                        placeholder = stringResource(R.string.groupNameEnter),
                        onChange = { updatedText ->
                             sendCommand(UpdateGroupName(updatedText))
                        },
                        showClear = true,
                        singleLine = true,
                        error = dialogsState.groupEditDialog.errorName,
                    )

                    // group description
                    SessionOutlinedTextField(
                        text = dialogsState.groupEditDialog.inputtedDescription ?: "",
                        modifier = Modifier.qaTag(R.string.qa_conversation_settings_dialog_groupname_description_input)
                            .padding(top = LocalDimensions.current.xxsSpacing),
                        placeholder = stringResource(R.string.groupDescriptionEnter),
                        innerPadding = PaddingValues(LocalDimensions.current.smallSpacing),
                        minLines = 3,
                        maxLines = 12,
                        onChange = { updatedText ->
                             sendCommand(UpdateGroupDescription(updatedText))
                        },
                        showClear = true,
                        error = dialogsState.groupEditDialog.errorDescription,
                    )
                }
            },
            buttons = listOf(
                DialogButtonModel(
                    text = GetString(stringResource(id = R.string.save)),
                    enabled = dialogsState.groupEditDialog.saveEnabled,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_groupname_save),
                    onClick = { sendCommand(SetGroupText) }
                ),
                DialogButtonModel(
                    text = GetString(stringResource(R.string.cancel)),
                    color = LocalColors.current.danger,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_groupname_cancel),
                )
            )
        )
    }
}

@Composable
fun GroupAdminClearMessagesDialog(
    modifier: Modifier = Modifier,
    groupName: String,
    sendCommand: (ConversationSettingsViewModel.Commands) -> Unit,
){
    var deleteForEveryone by remember { mutableStateOf(false) }

    val context = LocalContext.current

    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            sendCommand(HideGroupAdminClearMessagesDialog)
        },
        title = annotatedStringResource(R.string.clearMessages),
        text =  annotatedStringResource(Phrase.from(context, R.string.clearMessagesGroupAdminDescriptionUpdated)
            .put(GROUP_NAME_KEY, groupName)
            .format()),
        content = {
            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(stringResource(R.string.clearOnThisDevice)),
                    qaTag = GetString(R.string.qa_conversation_settings_clear_messages_radio_device),
                    selected = !deleteForEveryone
                )
            ) {
                deleteForEveryone = false
            }

            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(stringResource(R.string.clearMessagesForEveryone)),
                    qaTag = GetString(R.string.qa_conversation_settings_clear_messages_radio_everyone),
                    selected = deleteForEveryone,
                )
            ) {
                deleteForEveryone = true
            }
        },
        buttons = listOf(
            DialogButtonModel(
                text = GetString(stringResource(id = R.string.clear)),
                color = LocalColors.current.danger,
                onClick = {
                    // clear messages based on chosen option
                    sendCommand(
                        if(deleteForEveryone) ClearMessagesGroupEveryone
                        else ClearMessagesGroupDeviceOnly
                    )
                }
            ),
            DialogButtonModel(
                GetString(stringResource(R.string.cancel))
            )
        )
    )
}

@Preview
@Composable
fun PreviewNicknameSetDialog() {
    PreviewTheme {
        ConversationSettingsDialogs(
            dialogsState = ConversationSettingsViewModel.DialogsState(
                nicknameDialog = ConversationSettingsViewModel.NicknameDialogData(
                    name = "Rick",
                    currentNickname = "Razza",
                    inputNickname = "Rickety",
                    setEnabled = true,
                    removeEnabled = true,
                    error = null,
                )
            ),
            sendCommand = {}
        )
    }
}


@Preview
@Composable
fun PreviewNicknameEmptyDialog() {
    PreviewTheme {
        ConversationSettingsDialogs(
            dialogsState = ConversationSettingsViewModel.DialogsState(
                nicknameDialog = ConversationSettingsViewModel.NicknameDialogData(
                    name = "Rick",
                    currentNickname = null,
                    inputNickname = null,
                    setEnabled = false,
                    removeEnabled = false,
                    error = null,
                )
            ),
            sendCommand = {}
        )
    }
}

@Preview
@Composable
fun PreviewNicknameEmptyWithInputDialog() {
    PreviewTheme {
        ConversationSettingsDialogs(
            dialogsState = ConversationSettingsViewModel.DialogsState(
                nicknameDialog = ConversationSettingsViewModel.NicknameDialogData(
                    name = "Rick",
                    currentNickname = null,
                    inputNickname = "Rickety",
                    setEnabled = true,
                    removeEnabled = false,
                    error = null,
                )
            ),
            sendCommand = {}
        )
    }
}

@Preview
@Composable
fun PreviewBaseGroupDialog() {
    PreviewTheme {
        ConversationSettingsDialogs(
            dialogsState = ConversationSettingsViewModel.DialogsState(
                groupEditDialog = ConversationSettingsViewModel.GroupEditDialog(
                    currentName = "the Crew",
                    inputName = null,
                    currentDescription = null,
                    inputtedDescription = null,
                    saveEnabled = true,
                    errorName = null,
                    errorDescription = null,
                )
            ),
            sendCommand = {}
        )
    }
}

@Preview
@Composable
fun PreviewClearAllMsgGroupDialog() {
    PreviewTheme {
        ConversationSettingsDialogs(
            dialogsState = ConversationSettingsViewModel.DialogsState(
                groupAdminClearMessagesDialog = ConversationSettingsViewModel.GroupAdminClearMessageDialog("Testy")
            ),
            sendCommand = {}
        )
    }
}