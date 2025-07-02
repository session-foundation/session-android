package org.thoughtcrime.securesms.conversation.v2

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.ClearEmoji
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.ConfirmRecreateGroup
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.HideClearEmoji
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.HideDeleteEveryoneDialog
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.HideRecreateGroupConfirm
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.HideSimpleDialog
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.MarkAsDeletedForEveryone
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.MarkAsDeletedLocally
import org.thoughtcrime.securesms.conversation.v2.ConversationViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.groups.compose.CreateGroupScreen
import org.thoughtcrime.securesms.openUrl
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.SimpleSessionProCTA
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionMaterialTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationV2Dialogs(
    dialogsState: ConversationViewModel.DialogsState,
    sendCommand: (ConversationViewModel.Commands) -> Unit
){
    SessionMaterialTheme {
        // open link confirmation
        if(!dialogsState.openLinkDialogUrl.isNullOrEmpty()){
            OpenURLAlertDialog(
                url = dialogsState.openLinkDialogUrl,
                onDismissRequest = {
                    // hide dialog
                    sendCommand(ShowOpenUrlDialog(null))
                }
            )
        }

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

        // delete message(s)
        if(dialogsState.deleteEveryone != null){
            val data = dialogsState.deleteEveryone
            var deleteForEveryone by remember { mutableStateOf(data.defaultToEveryone)}

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
                    sendCommand(ConversationViewModel.Commands.HideRecreateGroup)
                },
                sheetState = state,
                dragHandle = null
            ) {
                CreateGroupScreen(
                    fromLegacyGroupId = dialogsState.recreateGroupData.legacyGroupId,
                    onNavigateToConversationScreen = { threadId ->
                        sendCommand(ConversationViewModel.Commands.NavigateToConversation(threadId))
                    },
                    onBack = {
                        sendCommand(ConversationViewModel.Commands.HideRecreateGroup)
                    },
                    onClose = {
                        sendCommand(ConversationViewModel.Commands.HideRecreateGroup)
                    },
                )
            }
        }

        // Pro CTA
        if (dialogsState.sessionProCharLimitCTA) {
            val context = LocalContext.current

            SimpleSessionProCTA(
                heroImage = R.drawable.cta_hero_char_limit,
                text = stringResource(R.string.proCallToActionLongerMessages),
                features = listOf(
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLongerMessages)),
                    CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
                    CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
                ),
                onUpgrade = {
                    sendCommand(ConversationViewModel.Commands.HideSessionProCTA)
                    context.openUrl(NonTranslatableStringConstants.SESSION_DOWNLOAD_URL) //todo PRO get the proper UR: once we have it
                },
                onCancel = {
                    sendCommand(ConversationViewModel.Commands.HideSessionProCTA)
                }
            )

        }
    }
}

@Preview
@Composable
fun PreviewURLDialog(){
    PreviewTheme {
        ConversationV2Dialogs(
            dialogsState = ConversationViewModel.DialogsState(
                openLinkDialogUrl = "https://google.com"
            ),
            sendCommand = {}
        )
    }
}
