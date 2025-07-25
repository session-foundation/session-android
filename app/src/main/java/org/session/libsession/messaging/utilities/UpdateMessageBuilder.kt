package org.session.libsession.messaging.utilities

import android.content.Context
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.calls.CallMessageType.CALL_FIRST_MISSED
import org.session.libsession.messaging.calls.CallMessageType.CALL_INCOMING
import org.session.libsession.messaging.calls.CallMessageType.CALL_MISSED
import org.session.libsession.messaging.calls.CallMessageType.CALL_OUTGOING
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage.Kind.SCREENSHOT
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DISAPPEARING_MESSAGES_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.getGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log

object UpdateMessageBuilder {
    const val TAG = "UpdateMessageBuilder"


    val storage = MessagingModuleConfiguration.shared.storage
    val usernameUtils = MessagingModuleConfiguration.shared.usernameUtils

    private fun getGroupMemberName(memberId: String, groupId: AccountId? = null) =
        usernameUtils.getContactNameWithAccountID(memberId, groupId)

    @JvmStatic
    fun buildGroupUpdateMessage(
        context: Context,
        groupV2Id: AccountId?, // null for legacy groups
        updateMessageData: UpdateMessageData,
        configFactory: ConfigFactoryProtocol,
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
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                            .format()
                    }
                    2 -> {
                        Phrase.from(context, R.string.legacyGroupMemberTwoNew)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                            .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1), groupV2Id))
                            .format()
                    }
                    else -> {
                        val newMemberCountMinusOne = newMemberCount - 1
                        Phrase.from(context, R.string.legacyGroupMemberNewMultiple)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                            .put(COUNT_KEY, newMemberCountMinusOne)
                            .format()
                    }
                }
            }

            // --- Group member(s) removed ---
            is UpdateMessageData.Kind.GroupMemberRemoved -> {
                val userPublicKey = storage.getUserPublicKey()!!

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
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                                .format()
                            2 -> Phrase.from(context, R.string.groupRemovedTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1), groupV2Id))
                                .format()
                            else -> Phrase.from(context, R.string.groupRemovedMultiple)
                                    .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
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
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                                .format()
                            2 -> Phrase.from(context, R.string.groupRemovedTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1), groupV2Id))
                                .format()
                            else -> Phrase.from(context, R.string.groupRemovedMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
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
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                            .format()
                        2 -> Phrase.from(context, R.string.groupMemberLeftTwo)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                            .put(OTHER_NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(1), groupV2Id))
                            .format()
                        else -> Phrase.from(context, R.string.groupMemberLeftMultiple)
                            .put(NAME_KEY, getGroupMemberName(updateData.updatedMembers.elementAt(0), groupV2Id))
                            .put(COUNT_KEY, updateData.updatedMembers.size - 1)
                            .format()
                    }
                }
            }
            is UpdateMessageData.Kind.GroupAvatarUpdated -> context.getString(R.string.groupDisplayPictureUpdated)
            is UpdateMessageData.Kind.GroupExpirationUpdated -> {
                buildExpirationTimerMessage(context, updateData.updatedExpiration, isGroup = true,
                    senderId = updateData.updatingAdmin,
                    isOutgoing = isOutgoing,
                    timestamp = messageTimestamp,
                    expireStarted = expireStarted
                )
            }
            is UpdateMessageData.Kind.GroupMemberUpdated -> {
                val userPublicKey = storage.getUserPublicKey()!!
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
                                if (historyShared) R.string.groupMemberNewHistory else R.string.groupMemberNew)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .format()
                            number == 2 && containsUser -> Phrase.from(context,
                                    if (historyShared) R.string.groupMemberNewYouHistoryTwo else R.string.groupInviteYouAndOtherNew)
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.first { it != userPublicKey }, groupV2Id))
                                .format()
                            number == 2 -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberNewHistoryTwo else R.string.groupMemberNewTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.last(), groupV2Id))
                                .format()
                            containsUser -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberNewYouHistoryMultiple else R.string.groupInviteYouAndMoreNew)
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            number > 0 -> Phrase.from(context,
                                if (historyShared) R.string.groupMemberNewHistoryMultiple else R.string.groupMemberNewMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
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
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .format()
                            number == 2 && containsUser -> Phrase.from(context,
                                R.string.groupPromotedYouTwo)
                                .put(OTHER_NAME_KEY,  getGroupMemberName(updateData.sessionIds.first{ it != userPublicKey }, groupV2Id))
                                .format()
                            number == 2 -> Phrase.from(context,
                                R.string.adminTwoPromotedToAdmin)
                                .put(NAME_KEY,  getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .put(OTHER_NAME_KEY,  getGroupMemberName(updateData.sessionIds.last(), groupV2Id))
                                .format()
                            containsUser -> Phrase.from(context,
                                R.string.groupPromotedYouMultiple)
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            else -> Phrase.from(context,
                                R.string.adminMorePromotedToAdmin)
                                .put(NAME_KEY,  getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
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
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .format()
                            number == 2 && containsUser -> Phrase.from(context,
                                R.string.groupRemovedYouTwo)
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.first { it != userPublicKey }, groupV2Id))
                                .format()
                            number == 2 -> Phrase.from(context,
                                R.string.groupRemovedTwo)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .put(OTHER_NAME_KEY, getGroupMemberName(updateData.sessionIds.last(), groupV2Id))
                                .format()
                            containsUser -> Phrase.from(context,
                                R.string.groupRemovedYouMultiple)
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                            else -> Phrase.from(context,
                                R.string.groupRemovedMultiple)
                                .put(NAME_KEY, getGroupMemberName(updateData.sessionIds.first(), groupV2Id))
                                .put(COUNT_KEY, updateData.sessionIds.size - 1)
                                .format()
                        }
                    }
                    null -> ""
                }
            }
            is UpdateMessageData.Kind.GroupInvitation -> {
                val approved = configFactory.getGroup(AccountId(updateData.groupAccountId))?.invited == false
                val inviterName = updateData.invitingAdminName?.takeIf { it.isNotEmpty() } ?: getGroupMemberName(updateData.invitingAdminId, groupV2Id)
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

    fun buildExpirationTimerMessage(
        context: Context,
        mode: ExpiryMode,
        isGroup: Boolean,  // Note: isGroup should cover both closed groups AND communities
        senderId: String?,
        isOutgoing: Boolean,
    ): CharSequence {
        if (!isOutgoing && senderId == null) {
            Log.w(TAG, "buildExpirationTimerMessage: Cannot build for outgoing message when senderId is null.")
            return ""
        }

        val senderName = if (isOutgoing) context.getString(R.string.you) else getGroupMemberName(senderId!!)

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


    @Deprecated("Use the version with ExpiryMode instead. This will be removed in a future release.")
    fun buildExpirationTimerMessage(
        context: Context,
        duration: Long,
        isGroup: Boolean, // Note: isGroup should cover both closed groups AND communities
        senderId: String? = null,
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
            senderId = senderId,
            isOutgoing = isOutgoing,
        )
    }

    fun buildDataExtractionMessage(context: Context,
                                   kind: DataExtractionNotificationInfoMessage.Kind,
                                   senderId: String? = null): CharSequence {

        val senderName = if (senderId != null) getGroupMemberName(senderId) else context.getString(R.string.unknown)

        return when (kind) {
            SCREENSHOT  -> Phrase.from(context, R.string.screenshotTaken)
                .put(NAME_KEY, senderName)
                .format()

            MEDIA_SAVED -> Phrase.from(context, R.string.attachmentsMediaSaved)
                .put(NAME_KEY, senderName)
                .format()
        }
    }

    fun buildCallMessage(context: Context, type: CallMessageType, senderId: String): String {
        val senderName = usernameUtils.getContactNameWithAccountID(senderId)

        return when (type) {
            CALL_INCOMING -> Phrase.from(context, R.string.callsCalledYou).put(NAME_KEY, senderName)
                .format().toString()

            CALL_OUTGOING -> Phrase.from(context, R.string.callsYouCalled).put(NAME_KEY, senderName)
                .format().toString()

            CALL_MISSED, CALL_FIRST_MISSED -> Phrase.from(context, R.string.callsMissedCallFrom)
                .put(NAME_KEY, senderName).format().toString()
        }
    }
}
