package org.thoughtcrime.securesms.conversation.v3

import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.recipients.MessageType
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.ui.dialog.SimpleDialogData
import org.thoughtcrime.securesms.util.UserProfileModalData

data class ConversationScrollState(
    val isNearBottom: Boolean,
    val isFullyScrolled: Boolean,
    val firstVisibleIndex: Int,
    val lastVisibleIndex: Int,
    val totalItemCount: Int,
)

data class ConversationDialogsState(
    val showSimpleDialog: SimpleDialogData? = null,
    val urlDialog: LinkType? = null,
    val clearAllEmoji: ClearAllEmoji? = null,
    val deleteEveryone: DeleteForEveryoneDialogData? = null,
    val recreateGroupConfirm: Boolean = false,
    val recreateGroupData: RecreateGroupDialogData? = null,
    val userProfileModal: UserProfileModalData? = null,
    val attachmentDownload: ConfirmAttachmentDownloadDialogData? = null
)

data class ConfirmAttachmentDownloadDialogData(
    val attachment: DatabaseAttachment,
    val conversationName: String
)

data class RecreateGroupDialogData(
    val legacyGroupId: String,
)

data class DeleteForEveryoneDialogData(
    val messages: Set<MessageRecord>,
    val messageType: MessageType,
    val defaultToEveryone: Boolean,
    val everyoneEnabled: Boolean,
    val deleteForEveryoneLabel: String,
    val warning: String? = null
)

data class ClearAllEmoji(
    val emoji: String,
    val messageId: MessageId
)
