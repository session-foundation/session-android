package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.AUTHOR_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DISAPPEARING_MESSAGES_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.MESSAGE_SNIPPET_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.GroupThreadStatus
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.ui.getSubbedCharSequence
import javax.inject.Inject


class MessageFormatter @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val recipientRepository: RecipientRepository,
    private val loginStateRepository: LoginStateRepository,
) {

    fun formatMessageBody(
        context: Context,
        message: MessageRecord,
        threadRecipient: Recipient
    ): CharSequence {
        when {
            message.isGroupUpdateMessage -> {
                val updateMessageData: UpdateMessageData = message.getGroupUpdateMessage() ?: return ""

                val text = SpannableString(
                    buildGroupUpdateMessage(
                        context = context,
                        updateMessageData = updateMessageData,
                        isOutgoing = message.isOutgoing,
                        messageTimestamp = message.timestamp,
                        expireStarted = message.expireStarted
                    )
                )

                if (updateMessageData.isGroupErrorQuitKind()) {
                    text.setSpan(
                        ForegroundColorSpan(ThemeUtil.getThemedColor(context, R.attr.danger)),
                        0,
                        text.length,
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                } else if (updateMessageData.isGroupLeavingKind()) {
                    text.setSpan(
                        ForegroundColorSpan(
                            ThemeUtil.getThemedColor(
                                context,
                                android.R.attr.textColorTertiary
                            )
                        ), 0, text.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                }

                return text
            }
            message.messageContent is DisappearingMessageUpdate -> {
                val isGroup = threadRecipient.isGroupOrCommunityRecipient
                return buildExpirationTimerMessage(
                    context,
                    (message.messageContent as DisappearingMessageUpdate).expiryMode,
                    isGroup,
                    message.individualRecipient,
                    message.isOutgoing
                )
            }
            message.isDataExtractionNotification -> {
                if (message.isScreenshotNotification) return SpannableString(
                    buildDataExtractionMessage(
                        context = context,
                        kind = DataExtractionNotificationInfoMessage.Kind.SCREENSHOT,
                        sender = message.individualRecipient
                    )
                )
                else if (message.isMediaSavedNotification) return SpannableString(
                    buildDataExtractionMessage(
                        context = context,
                        kind = DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED,
                        sender = message.individualRecipient
                    )
                )
            }
            message.isCallLog -> {
                val callType = if (message.isIncomingCall) {
                    CallMessageType.CALL_INCOMING
                } else if (message.isOutgoingCall) {
                    CallMessageType.CALL_OUTGOING
                } else if (message.isMissedCall) {
                    CallMessageType.CALL_MISSED
                } else {
                    CallMessageType.CALL_FIRST_MISSED
                }

                return SpannableString(
                    buildCallMessage(
                        context = context,
                        type = callType,
                        sender = message.individualRecipient
                    )
                )
            }
            message.isMessageRequestResponse -> {
                return if (message.recipient.isSelf) {
                    // you accepted the user's request
                    context.getSubbedCharSequence(
                        R.string.messageRequestYouHaveAccepted,
                        NAME_KEY to threadRecipient.displayName()
                    )
                } else {
                    // the user accepted your request
                    context.getString(R.string.messageRequestsAccepted)
                }
            }
        }

        return SpannableString(message.body)
    }

    fun formatThreadSnippet(
        context: Context,
        thread: ThreadRecord
    ): CharSequence {
        val lastMessage = thread.lastMessage

        return when {
            thread.groupThreadStatus == GroupThreadStatus.Kicked -> {
                Phrase.from(context, R.string.groupRemovedYou)
                    .put(GROUP_NAME_KEY, thread.recipient.displayName())
                    .format()
                    .toString()
            }

            thread.groupThreadStatus == GroupThreadStatus.Destroyed -> {
                Phrase.from(context, R.string.groupDeletedMemberDescription)
                    .put(GROUP_NAME_KEY, thread.recipient.displayName())
                    .format()
                    .toString()
            }

            lastMessage == null -> {
                // no need to display anything if there are no messages
                ""
            }

            // We will show different text for community invitation on the thread list
            lastMessage.isOpenGroupInvitation -> {
                context.getString(R.string.communityInvitation)
            }

            else -> {
                val bodyText = formatMessageBody(
                    context = context,
                    message = lastMessage,
                    threadRecipient = thread.recipient
                )

                // This is used to show a placeholder text for MMS messages in the snippet,
                // for example, "<image> Attachment"
                val mmsPlaceholderBody = (lastMessage as? MmsMessageRecord)?.slideDeck?.body

                val text = when {
                    // If both body and placeholder are blank, return empty string
                    bodyText.isBlank() && mmsPlaceholderBody.isNullOrBlank() -> ""

                    // If both body and placeholder are non-blank, combine them
                    bodyText.isNotBlank() && !mmsPlaceholderBody.isNullOrBlank() ->
                        SpannableStringBuilder(mmsPlaceholderBody)
                            .append(": ")
                            .append(bodyText)

                    // If only placeholder is non-blank, use it
                    !mmsPlaceholderBody.isNullOrBlank() -> mmsPlaceholderBody

                    // Otherwise, use the body text
                    else -> bodyText
                }

                when {
                    // There are certain messages that we want to keep their formatting
                    lastMessage.groupUpdateMessage?.isGroupLeavingKind() == true ||
                            lastMessage.groupUpdateMessage?.isGroupErrorQuitKind() == true -> {
                        text
                    }

                    // For group/community threads, we want to prefix the snippet with the author's name
                    thread.recipient.isGroupOrCommunityRecipient -> {
                        val prefix = if (lastMessage.isOutgoing) {
                            context.getString(R.string.you)
                        } else {
                            lastMessage.individualRecipient.displayName()
                        }

                        Phrase.from(context.getString(R.string.messageSnippetGroup))
                            .put(AUTHOR_KEY, prefix)
                            .put(MESSAGE_SNIPPET_KEY, text.toString())
                            .format()
                    }

                    // For all other messages, convert to plain string to avoid weird snippet appearances
                    else -> text.toString()
                }
            }
        }
    }

    private fun buildGroupUpdateMessage(
        context: Context,
        updateMessageData: UpdateMessageData,
        isOutgoing: Boolean,
        messageTimestamp: Long,
        expireStarted: Long,
    ): CharSequence {
        val updateData = updateMessageData.kind ?: return ""

        return when (updateData) {
            // --- Group created or joined ---
            is UpdateMessageData.Kind.GroupCreation -> {
                if (!isOutgoing) {
                    context.getText(R.string.legacyGroupMemberYouNew)
                } else {
                    "" // We no longer add a string like `disappearingMessagesNewGroup` ("You created a new group") and leave the group with its default empty state
                }
            }

            // --- Group name changed ---
            is UpdateMessageData.Kind.GroupNameChange -> {
                Phrase.from(context, R.string.groupNameNew)
                    .put(GROUP_NAME_KEY, updateData.name)
                    .format()
            }

            // --- Group member(s) were added ---
            is UpdateMessageData.Kind.GroupMemberAdded -> {

                val newMemberCount = updateData.updatedMembers.size

                // Note: We previously differentiated between members added by us Vs. members added by someone
                // else via checking against `isOutgoing` - but now we use the same strings regardless.
                when (newMemberCount) {
                    0 -> {
                        Log.w(TAG, "Somehow asked to add zero new members to group - this should never happen.")
                        return ""
                    }
                    1 -> {
                        Phrase.from(context, R.string.legacyGroupMemberNew)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                            .format()
                    }
                    2 -> {
                        Phrase.from(context, R.string.legacyGroupMemberTwoNew)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                            .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1)))
                            .format()
                    }
                    else -> {
                        val newMemberCountMinusOne = newMemberCount - 1
                        Phrase.from(context, R.string.legacyGroupMemberNewMultiple)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                            .put(COUNT_KEY, newMemberCountMinusOne)
                            .format()
                    }
                }
            }

            // --- Group member(s) removed ---
            is UpdateMessageData.Kind.GroupMemberRemoved -> {
                val userPublicKey = loginStateRepository.requireLocalNumber()

                // 1st case: you are part of the removed members
                return if (userPublicKey in updateData.updatedMembers) {
                    if (isOutgoing) context.getText(R.string.groupMemberYouLeft) // You chose to leave
                    else Phrase.from(context, R.string.groupRemovedYou)            // You were forced to leave
                        .put(GROUP_NAME_KEY, updateData.groupName)
                        .format()
                }
                else // 2nd case: you are not part of the removed members
                {
                    // a.) You are the person doing the removing of one or more members
                    if (isOutgoing) {
                        when (updateData.updatedMembers.size) {
                            0 -> {
                                Log.w(TAG, "Somehow you asked to remove zero members.")
                                "" // Return an empty string - we don't want to show the error in the conversation
                            }
                            1 -> Phrase.from(context, R.string.groupRemoved)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                                .format()
                            2 -> Phrase.from(context, R.string.groupRemovedTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1)))
                                .format()
                            else -> Phrase.from(context, R.string.groupRemovedMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                                .put(COUNT_KEY, updateData.updatedMembers.size - 1)
                                .format()
                        }
                    }
                    else // b.) Someone else is the person doing the removing of one or more members
                    {
                        // Note: I don't think we're doing "Alice removed Bob from the group"-type
                        // messages anymore - just "Bob was removed from the group" - so this block
                        // is identical to the one above, but I'll leave it like this until I can
                        // confirm that this is the case.
                        when (updateData.updatedMembers.size) {
                            0 -> {
                                Log.w(TAG, "Somehow someone else asked to remove zero members.")
                                "" // Return an empty string - we don't want to show the error in the conversation
                            }
                            1 -> Phrase.from(context, R.string.groupRemoved)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                                .format()
                            2 -> Phrase.from(context, R.string.groupRemovedTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1)))
                                .format()
                            else -> Phrase.from(context, R.string.groupRemovedMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                                .put(COUNT_KEY, updateData.updatedMembers.size - 1)
                                .format()
                        }
                    }
                }
            }
            is UpdateMessageData.Kind.GroupMemberLeft -> {
                if (isOutgoing) context.getText(R.string.groupMemberYouLeft)
                else {
                    when (updateData.updatedMembers.size) {
                        0 -> {
                            Log.w(TAG, "Somehow zero members left the group.")
                            "" // Return an empty string - we don't want to show the error in the conversation
                        }
                        1 -> Phrase.from(context, R.string.groupMemberLeft)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                            .format()
                        2 -> Phrase.from(context, R.string.groupMemberLeftTwo)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                            .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1)))
                            .format()
                        else -> Phrase.from(context, R.string.groupMemberLeftMultiple)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0)))
                            .put(COUNT_KEY, updateData.updatedMembers.size - 1)
                            .format()
                    }
                }
            }
            is UpdateMessageData.Kind.GroupAvatarUpdated -> context.getString(R.string.groupDisplayPictureUpdated)
            is UpdateMessageData.Kind.GroupExpirationUpdated -> {
                buildExpirationTimerMessage(
                    context = context,
                    duration = updateData.updatedExpiration,
                    isGroup = true,
                    sender = recipientRepository.getRecipientSync(updateData.updatingAdmin.toAddress()),
                    isOutgoing = isOutgoing,
                    timestamp = messageTimestamp,
                    expireStarted = expireStarted
                )
            }
            is UpdateMessageData.Kind.GroupMemberUpdated -> {
                val userPublicKey = loginStateRepository.requireLocalNumber()
                val number = updateData.sessionIds.size
                val containsUser = updateData.sessionIds.contains(userPublicKey)
                val historyShared = updateData.historyShared
                when (updateData.type) {
                    UpdateMessageData.MemberUpdateType.ADDED -> {
                        when {
                            number == 1 && containsUser -> Phrase.from(context,
                                if (historyShared) R.string.groupInviteYouHistory else R.string.groupInviteYou)
                                .format()
                            number == 1 -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberInvitedHistory else R.string.groupMemberNew)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .format()
                            number == 2 && containsUser -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberNewYouHistoryTwo else R.string.groupInviteYouAndOtherNew)
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.first { it != userPublicKey }))
                                .format()
                            number == 2 -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberInvitedHistoryTwo else R.string.groupMemberNewTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.last()))
                                .format()
                            containsUser -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberNewYouHistoryMultiple else R.string.groupInviteYouAndMoreNew)
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            number > 0 -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberInvitedHistoryMultiple else R.string.groupMemberNewMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            else -> ""
                        }
                    }

                    UpdateMessageData.MemberUpdateType.PROMOTED -> {
                        when {
                            number == 1 && containsUser -> context.getString(
                                R.string.groupPromotedYou
                            )
                            number == 1 -> Phrase.from(context,
                                R.string.adminPromotedToAdmin)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .format()
                            number == 2 && containsUser -> Phrase.from(context,
                                R.string.groupPromotedYouTwo)
                                .put(OTHER_NAME_KEY,  getGroupMemberName(updateData.sessionIds.first{ it != userPublicKey }))
                                .format()
                            number == 2 -> Phrase.from(context,
                                R.string.adminTwoPromotedToAdmin)
                                .put(NAME_KEY,  getGroupMemberName(updateData.sessionIds.first()))
                                .put(OTHER_NAME_KEY,  getGroupMemberName(updateData.sessionIds.last()))
                                .format()
                            containsUser -> Phrase.from(context,
                                R.string.groupPromotedYouMultiple)
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            else -> Phrase.from(context,
                                R.string.adminMorePromotedToAdmin)
                                .put(NAME_KEY,  getGroupMemberName(updateData.sessionIds.first()))
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                        }
                    }
                    UpdateMessageData.MemberUpdateType.REMOVED -> {

                        when {
                            number == 1 && containsUser -> Phrase.from(context,
                                R.string.groupRemovedYouGeneral).format()
                            number == 1 -> Phrase.from(context,
                                R.string.groupRemoved)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .format()
                            number == 2 && containsUser -> Phrase.from(context,
                                R.string.groupRemovedYouTwo)
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.first { it != userPublicKey }))
                                .format()
                            number == 2 -> Phrase.from(context,
                                R.string.groupRemovedTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.last()))
                                .format()
                            containsUser -> Phrase.from(context,
                                R.string.groupRemovedYouMultiple)
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            else -> Phrase.from(context,
                                R.string.groupRemovedMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first()))
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                        }
                    }
                    null -> ""
                }
            }
            is UpdateMessageData.Kind.GroupInvitation -> {
                val approved = configFactory.getGroup(AccountId(updateData.groupAccountId))?.invited == false
                val inviterName = updateData.invitingAdminName?.takeIf { it.isNotEmpty() } ?: getGroupMemberName(
                    updateData.invitingAdminId
                )
                return if (!approved) {
                    Phrase.from(context, R.string.messageRequestGroupInvite)
                        .put(NAME_KEY, inviterName)
                        .put(GROUP_NAME_KEY, updateData.groupName)
                        .format()
                } else {
                    context.getString(R.string.groupInviteYou)
                }
            }
            is UpdateMessageData.Kind.OpenGroupInvitation -> ""
            is UpdateMessageData.Kind.GroupLeaving -> {
                return if (isOutgoing) {
                    context.getString(R.string.leaving)
                } else {
                    ""
                }
            }
            is UpdateMessageData.Kind.GroupErrorQuit -> {
                return Phrase.from(context, R.string.groupLeaveErrorFailed)
                    .put(GROUP_NAME_KEY, updateData.groupName)
                    .format()
            }
        }
    }

    private fun getGroupMemberName(senderAddress: String): String {
        return recipientRepository.getRecipientSync(Address.fromSerialized(senderAddress))
            .displayName()
    }

    fun buildExpirationTimerMessage(
        context: Context,
        mode: ExpiryMode,
        isGroup: Boolean,  // Note: isGroup should cover both closed groups AND communities
        sender: Recipient,
        isOutgoing: Boolean,
    ): CharSequence {
        val senderName = if (isOutgoing) context.getString(R.string.you) else sender.displayName()

        // Case 1.) Disappearing messages have been turned off..
        if (mode == ExpiryMode.NONE) {
            // ..by you..
            return if (isOutgoing) {
                // in a group
                if(isGroup) context.getText(R.string.disappearingMessagesTurnedOffYouGroup)
                // 1on1
                else context.getText(R.string.disappearingMessagesTurnedOffYou)
            }
            else // ..or by someone else.
            {
                Phrase.from(context,
                    // in a group
                    if(isGroup) R.string.disappearingMessagesTurnedOffGroup
                    // 1on1
                    else R.string.disappearingMessagesTurnedOff
                )
                    .put(NAME_KEY, senderName)
                    .format()
            }
        }

        // Case 2.) Disappearing message settings have been changed but not turned off.
        val time = ExpirationUtil.getExpirationDisplayValue(context, mode.duration)
        val action = if (mode is ExpiryMode.AfterSend) {
            context.getString(R.string.disappearingMessagesTypeSent)
        } else {
            context.getString(R.string.disappearingMessagesTypeRead)
        }

        //..by you..
        if (isOutgoing) {
            return if (isGroup) {
                Phrase.from(context, R.string.disappearingMessagesSetYou)
                    .put(TIME_KEY, time)
                    .put(DISAPPEARING_MESSAGES_TYPE_KEY, action)
                    .format()
            } else {
                // 1-on-1 conversation
                Phrase.from(context, R.string.disappearingMessagesSetYou)
                    .put(TIME_KEY, time)
                    .put(DISAPPEARING_MESSAGES_TYPE_KEY, action)
                    .format()
            }
        }
        else // ..or by someone else.
        {
            return Phrase.from(context, R.string.disappearingMessagesSet)
                .put(NAME_KEY, senderName)
                .put(TIME_KEY, time)
                .put(DISAPPEARING_MESSAGES_TYPE_KEY, action)
                .format()
        }
    }

    fun buildDataExtractionMessage(context: Context,
                                   kind: DataExtractionNotificationInfoMessage.Kind,
                                   sender: Recipient): CharSequence {

        return when (kind) {
            DataExtractionNotificationInfoMessage.Kind.SCREENSHOT -> Phrase.from(context, R.string.screenshotTaken)
                .put(NAME_KEY, sender.displayName())
                .format()

            DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED -> Phrase.from(context, R.string.attachmentsMediaSaved)
                .put(NAME_KEY, sender.displayName())
                .format()
        }
    }

    fun buildCallMessage(context: Context, type: CallMessageType, sender: Recipient): String {
        return when (type) {
            CallMessageType.CALL_INCOMING -> Phrase.from(context, R.string.callsCalledYou).put(NAME_KEY, sender.displayName())
                .format().toString()

            CallMessageType.CALL_OUTGOING -> Phrase.from(context, R.string.callsYouCalled).put(NAME_KEY, sender.displayName())
                .format().toString()

            CallMessageType.CALL_MISSED, CallMessageType.CALL_FIRST_MISSED -> Phrase.from(context, R.string.callsMissedCallFrom)
                .put(NAME_KEY, sender.displayName()).format().toString()
        }
    }

    @Deprecated("Use the version with ExpiryMode instead. This will be removed in a future release.")
    fun buildExpirationTimerMessage(
        context: Context,
        duration: Long,
        isGroup: Boolean, // Note: isGroup should cover both closed groups AND communities
        sender: Recipient,
        isOutgoing: Boolean = false,
        timestamp: Long,
        expireStarted: Long
    ): CharSequence {
        return buildExpirationTimerMessage(
            context,
            mode = when {
                duration == 0L -> ExpiryMode.NONE
                timestamp >= expireStarted -> ExpiryMode.AfterSend(duration) // Not the greatest logic here but keeping it for backwards compatibility, can be removed once migrated over
                else -> ExpiryMode.AfterRead(duration)
            },
            isGroup = isGroup,
            sender = sender,
            isOutgoing = isOutgoing,
        )
    }

    companion object {
        private const val TAG = "MessageFormatter"
    }
}