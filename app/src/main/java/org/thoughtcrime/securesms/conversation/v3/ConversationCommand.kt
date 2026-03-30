package org.thoughtcrime.securesms.conversation.v3

import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.UserProfileModalCommands

/**
 * Shared feature command contract for the conversation v3 flow.
 *
 * The ViewModel handles the full [ConversationCommand] set, while narrower UI layers can depend on
 * just the sub-scope they need, such as [MessageCommand].
 */
sealed interface ConversationCommand {
    sealed interface NavigationCommand : ConversationCommand
    sealed interface ScreenCommand : ConversationCommand
    sealed interface MessageCommand : ConversationCommand
    sealed interface DialogCommand : ConversationCommand

    data class GoTo(val destination: ConversationV3Destination) : NavigationCommand

    /** Compose reports current scroll state so the VM can derive scroll-dependent UI from it. */
    data class OnScrollStateChanged(val scrollState: ConversationScrollState) : ScreenCommand

    data object ScrollToBottom : ScreenCommand

    data class ScrollToMessage(
        val messageId: MessageId,
        val smoothScroll: Boolean = true,
        val highlight: Boolean = true,
    ) : ScreenCommand, MessageCommand

    data class HandleLink(val url: String) : MessageCommand
    data object HideUrlDialog : DialogCommand
    data class ClearEmoji(val emoji: String, val messageId: MessageId) : DialogCommand
    data object HideDeleteEveryoneDialog : DialogCommand
    data object HideClearEmoji : DialogCommand
    data class MarkAsDeletedLocally(val messages: Set<MessageRecord>) : DialogCommand
    data class MarkAsDeletedForEveryone(val data: DeleteForEveryoneDialogData) : DialogCommand

    data class OpenOrJoinCommunity(val url: String) : DialogCommand

    data class DownloadAttachments(val attachment: DatabaseAttachment) : DialogCommand
    data object HideAttachmentDownloadDialog : DialogCommand

    data object HideSimpleDialog : DialogCommand

    data object ConfirmRecreateGroup : DialogCommand
    data object HideRecreateGroupConfirm : DialogCommand
    data object HideRecreateGroup : DialogCommand

    data object HideUserProfileModal : DialogCommand
    data class HandleUserProfileCommand(
        val upmCommand: UserProfileModalCommands
    ) : DialogCommand
}
