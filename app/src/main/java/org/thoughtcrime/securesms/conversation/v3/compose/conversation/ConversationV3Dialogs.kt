package org.thoughtcrime.securesms.conversation.v3.compose.conversation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.CONVERSATION_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.thoughtcrime.securesms.InputBarDialogs
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.ClearEmoji
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.ConfirmRecreateGroup
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.DownloadAttachments
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HandleUserProfileCommand
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideAttachmentDownloadDialog
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideClearEmoji
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideDeleteEveryoneDialog
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideUrlDialog
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideRecreateGroup
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideRecreateGroupConfirm
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideSimpleDialog
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.HideUserProfileModal
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.OpenOrJoinCommunity
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.MarkAsDeletedForEveryone
import org.thoughtcrime.securesms.conversation.v3.ConversationCommand.MarkAsDeletedLocally
import org.thoughtcrime.securesms.conversation.v3.ConversationDialogsState
import org.thoughtcrime.securesms.home.startconversation.group.CreateGroupScreen
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.ui.dialog.AlertDialog
import org.thoughtcrime.securesms.ui.dialog.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.UserProfileModal
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.dialog.LinkAlertDialog
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationV3Dialogs(
    dialogsState: ConversationDialogsState,
    inputBarDialogsState: InputbarViewModel.InputBarDialogsState,
    sendCommand: (ConversationCommand) -> Unit,
    sendInputBarCommand: (InputbarViewModel.Commands) -> Unit,
    onPostUserProfileModalAction: () -> Unit // a function called in the User Profile Modal once an action has been taken
){
    SessionMaterialTheme {
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

        // inputbar dialogs
        InputBarDialogs(
            inputBarDialogsState = inputBarDialogsState,
            sendCommand = sendInputBarCommand
        )

        // Link dialogs
        if(dialogsState.urlDialog != null){
            LinkAlertDialog(
                data = dialogsState.urlDialog,
                onDismissRequest = {
                    // hide dialog
                    sendCommand(HideUrlDialog)
                },
                openOrJoinCommunity = {
                    sendCommand(OpenOrJoinCommunity(it))
                }
            )
        }

        // delete message(s)
        if(dialogsState.deleteEveryone != null){
            val data = dialogsState.deleteEveryone
            var deleteForEveryone by retain { mutableStateOf(data.defaultToEveryone)}

            AlertDialog(
                onDismissRequest = {
                    // hide dialog
                    sendCommand(HideDeleteEveryoneDialog)
                },
                title = pluralStringResource(
                    R.plurals.deleteMessage,
                    data.messages.size,
                    data.messages.size
                ),
                text = pluralStringResource(
                    R.plurals.deleteMessageConfirm,
                    data.messages.size,
                    data.messages.size
                ),
                content = {
                    // add warning text, if any
                    data.warning?.let {
                        Text(
                            text = it,
                            textAlign = TextAlign.Center,
                            style = LocalType.current.small,
                            color = LocalColors.current.warning,
                            modifier = Modifier.padding(
                                top = LocalDimensions.current.xxxsSpacing,
                                bottom = LocalDimensions.current.xxsSpacing
                            )
                        )
                    }

                    DialogTitledRadioButton(
                        option = RadioOption(
                            value = Unit,
                            title = GetString(stringResource(R.string.deleteMessageDeviceOnly)),
                            qaTag = GetString(stringResource(R.string.qa_delete_message_device_only)),
                            selected = !deleteForEveryone
                        )
                    ) {
                        deleteForEveryone = false
                    }

                    DialogTitledRadioButton(
                        option = RadioOption(
                            value = Unit,
                            title = GetString(data.deleteForEveryoneLabel),
                            qaTag = GetString(stringResource(R.string.qa_delete_message_everyone)),
                            selected = deleteForEveryone,
                            enabled = data.everyoneEnabled
                        )
                    ) {
                        deleteForEveryone = true
                    }
                },
                buttons = listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.delete)),
                        color = LocalColors.current.danger,
                        onClick = {
                            // delete messages based on chosen option
                            sendCommand(
                                if(deleteForEveryone) MarkAsDeletedForEveryone(
                                    data.copy(defaultToEveryone = deleteForEveryone)
                                )
                                else MarkAsDeletedLocally(data.messages)
                            )
                        }
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            )
        }

        // Clear emoji
        if(dialogsState.clearAllEmoji != null){
            AlertDialog(
                onDismissRequest = {
                    // hide dialog
                    sendCommand(HideClearEmoji)
                },
                text = stringResource(R.string.emojiReactsClearAll).let { txt ->
                    Phrase.from(txt).put(EMOJI_KEY, dialogsState.clearAllEmoji.emoji).format().toString()
                },
                buttons = listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.clear)),
                        color = LocalColors.current.danger,
                        onClick = {
                            // delete emoji
                            sendCommand(
                                ClearEmoji(dialogsState.clearAllEmoji.emoji, dialogsState.clearAllEmoji.messageId)
                            )
                        }
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            )
        }

        if (dialogsState.recreateGroupConfirm) {
            AlertDialog(
                onDismissRequest = {
                    // hide dialog
                    sendCommand(HideRecreateGroupConfirm)
                },
                title = stringResource(R.string.recreateGroup),
                text = stringResource(R.string.legacyGroupChatHistory),
                buttons = listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.theContinue)),
                        color = LocalColors.current.danger,
                        onClick = {
                            sendCommand(ConfirmRecreateGroup)
                        }
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            )
        }

        if (dialogsState.recreateGroupData != null) {
            val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = {
                    sendCommand(HideRecreateGroup)
                },
                sheetState = state,
                dragHandle = null
            ) {
                CreateGroupScreen(
                    fromLegacyGroupId = dialogsState.recreateGroupData.legacyGroupId,
                    onNavigateToConversationScreen = { threadId ->
                        //todo convov3 implement in case we still want to recreate groups
                    },
                    onBack = {
                        sendCommand(HideRecreateGroup)
                    },
                    onClose = {
                        sendCommand(HideRecreateGroup)
                    },
                )
            }
        }

        // user profile modal
        if(dialogsState.userProfileModal != null){
            UserProfileModal(
                data = dialogsState.userProfileModal,
                onDismissRequest = {
                    sendCommand(HideUserProfileModal)
                },
                sendCommand = {
                    sendCommand(HandleUserProfileCommand(it))
                },
                onPostAction = onPostUserProfileModalAction
            )
        }


        // Attachment downloads
        if(dialogsState.attachmentDownload != null){
            AlertDialog(
                onDismissRequest = {
                    // hide dialog
                    sendCommand(HideAttachmentDownloadDialog)
                },
                title = annotatedStringResource(R.string.attachmentsAutoDownloadModalTitle),
                text = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.attachmentsAutoDownloadModalDescription)
                    .put(CONVERSATION_NAME_KEY, dialogsState.attachmentDownload.conversationName)
                    .format()),
                buttons = listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.download)),
                        onClick = {
                            sendCommand(
                                DownloadAttachments(attachment = dialogsState.attachmentDownload.attachment)
                            )
                        }
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            )
        }
    }
}

@Preview
@Composable
fun PreviewURLDialog(){
    PreviewTheme {
        ConversationV3Dialogs(
            dialogsState = ConversationDialogsState(
                urlDialog = LinkType.GenericLink("https://google.com")
            ),
            inputBarDialogsState = InputbarViewModel.InputBarDialogsState(),
            sendCommand = {},
            sendInputBarCommand = {},
            onPostUserProfileModalAction = {}
        )
    }
}
