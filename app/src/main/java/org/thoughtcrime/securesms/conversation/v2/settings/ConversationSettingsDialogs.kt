package org.thoughtcrime.securesms.conversation.v2.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.sp
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.ClearMessagesGroupDeviceOnly
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.ClearMessagesGroupEveryone
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.HideGroupAdminClearMessagesDialog
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.HideGroupEditDialog
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.HideNicknameDialog
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.HidePinCTADialog
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.HideProBadgeCTA
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.HideSimpleDialog
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.RemoveNickname
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.SetGroupText
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.SetNickname
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.UpdateGroupDescription
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.UpdateGroupName
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsViewModel.Commands.UpdateNickname
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GenericProCTA
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.PinProCTA
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.SimpleSessionProActivatedCTA
import org.thoughtcrime.securesms.ui.components.AnnotatedTextWithIcon
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme

@Composable
fun ConversationSettingsDialogs(
    dialogsState: ConversationSettingsViewModel.DialogsState,
    sendCommand: (ConversationSettingsViewModel.Commands) -> Unit
){
    val context = LocalContext.current

    //  Simple dialogs
    if (dialogsState.showSimpleDialog != null) {
        val buttons = mutableListOf<DialogButtonData>()
        if(dialogsState.showSimpleDialog.positiveText != null) {
            buttons.add(
                DialogButtonData(
                    text = GetString(dialogsState.showSimpleDialog.positiveText),
                    color = if (dialogsState.showSimpleDialog.positiveStyleDanger) LocalColors.current.danger
                    else LocalColors.current.text,
                    qaTag = dialogsState.showSimpleDialog.positiveQaTag,
                    onClick = dialogsState.showSimpleDialog.onPositive
                )
            )
        }
        if(dialogsState.showSimpleDialog.negativeText != null){
            buttons.add(
                DialogButtonData(
                    text = GetString(dialogsState.showSimpleDialog.negativeText),
                    qaTag = dialogsState.showSimpleDialog.negativeQaTag,
                    onClick = dialogsState.showSimpleDialog.onNegative
                )
            )
        }

        AlertDialog(
            onDismissRequest = {
                // hide dialog
                sendCommand(HideSimpleDialog)
            },
            title = annotatedStringResource(dialogsState.showSimpleDialog.title),
            text = annotatedStringResource(dialogsState.showSimpleDialog.message),
            showCloseButton = dialogsState.showSimpleDialog.showXIcon,
            buttons = buttons
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
                DialogButtonData(
                    text = GetString(stringResource(id = R.string.save)),
                    enabled = dialogsState.nicknameDialog.setEnabled,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_nickname_set),
                    onClick = { sendCommand(SetNickname) }
                ),
                DialogButtonData(
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
                        clearQaTag = R.string.qa_input_clear_name,
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
                        clearQaTag = R.string.qa_input_clear_description,
                        error = dialogsState.groupEditDialog.errorDescription,
                    )
                }
            },
            buttons = listOf(
                DialogButtonData(
                    text = GetString(stringResource(id = R.string.save)),
                    enabled = dialogsState.groupEditDialog.saveEnabled,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_groupname_save),
                    onClick = { sendCommand(SetGroupText) }
                ),
                DialogButtonData(
                    text = GetString(stringResource(R.string.cancel)),
                    color = LocalColors.current.danger,
                    qaTag = stringResource(R.string.qa_conversation_settings_dialog_groupname_cancel),
                )
            )
        )
    }

    // pin CTA
    if(dialogsState.pinCTA != null){
        PinProCTA(
            overTheLimit = dialogsState.pinCTA.overTheLimit,
            onDismissRequest = {
                sendCommand(HidePinCTADialog)
            }
        )
    }

    when(dialogsState.proBadgeCTA){
        is ConversationSettingsViewModel.ProBadgeCTA.Generic -> {
            GenericProCTA(
                onDismissRequest = {
                    sendCommand(HideProBadgeCTA)
                }
            )
        }

        is ConversationSettingsViewModel.ProBadgeCTA.Group -> {
            SimpleSessionProActivatedCTA(
                heroImage = R.drawable.cta_hero_group,
                title = stringResource(R.string.proGroupActivated),
                textContent = {
                    AnnotatedTextWithIcon(
                        modifier = Modifier
                            .fillMaxWidth(),
                        text = stringResource(R.string.proGroupActivatedDescription),
                        iconRes = R.drawable.ic_pro_badge,
                        iconSize = 40.sp to 18.sp,
                        style = LocalType.current.large,
                    )
                },
                onCancel = {
                    sendCommand(HideProBadgeCTA)
                }
            )
        }

        else -> {}
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
            DialogButtonData(
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
            DialogButtonData(
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

@Preview
@Composable
fun PreviewCTAGroupDialog() {
    PreviewTheme {
        ConversationSettingsDialogs(
            dialogsState = ConversationSettingsViewModel.DialogsState(
                proBadgeCTA = ConversationSettingsViewModel.ProBadgeCTA.Group
            ),
            sendCommand = {}
        )
    }
}