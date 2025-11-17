package org.session.libsession.messaging.messages.signal

import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import java.util.EnumSet

data class IncomingTextMessage(
    val message: String?,
    val sender: Address,
    val senderDeviceId: Int,
    val sentTimestampMillis: Long,
    val group: Address.GroupLike?,
    val push: Boolean,
    val expiresInMillis: Long,
    val expireStartedAt: Long,
    val unidentified: Boolean,
    val callType: Int,
    val hasMention: Boolean,
    val isOpenGroupInvitation: Boolean,
    val isSecureMessage: Boolean,
    val isGroupMessage: Boolean = false,
    val isGroupUpdateMessage: Boolean = false,
) {
    // Legacy code
    val protocol: Int get() = 31337

    // Legacy code
    val serviceCenterAddress: String get() = "GCM"

    // Legacy code
    val replyPathPresent: Boolean get() = true

    // Legacy code
    val pseudoSubject: String get() = ""

    // Legacy code
    val subscriptionId: Int get() = -1

    val callMessageType: CallMessageType? get() =
        CallMessageType.entries.getOrNull(callType)

    val isUnreadCallMessage: Boolean
        get() = callMessageType in EnumSet.of(
            CallMessageType.CALL_MISSED,
            CallMessageType.CALL_FIRST_MISSED,
        )

    init {
        check(!isGroupUpdateMessage || isGroupMessage) {
            "A message cannot be a group update message if it is not a group message"
        }
    }

    constructor(
        message: VisibleMessage,
        sender: Address,
        group: Address.GroupLike?,
        expiresInMillis: Long,
        expireStartedAt: Long,
    ): this(
        sender = sender,
        senderDeviceId = 1,
        sentTimestampMillis = message.sentTimestamp!!,
        message = message.text,
        group = group,
        expiresInMillis = expiresInMillis,
        expireStartedAt = expireStartedAt,
        unidentified = false,
        hasMention = message.hasMention,
        push = true,
        callType = -1,
        isOpenGroupInvitation = false,
        isSecureMessage = false,
    )

    constructor(
        callMessageType: CallMessageType,
        sender: Address,
        group: Address.GroupLike?,
        sentTimestampMillis: Long,
        expiresInMillis: Long,
        expireStartedAt: Long,
    ): this(
        message = null,
        sender = sender,
        senderDeviceId = 1,
        sentTimestampMillis = sentTimestampMillis,
        group = group,
        push = false,
        expiresInMillis = expiresInMillis,
        expireStartedAt = expireStartedAt,
        unidentified = false,
        callType = callMessageType.ordinal,
        hasMention = false,
        isOpenGroupInvitation = false,
        isSecureMessage = false,
    )

    companion object {
        fun fromOpenGroupInvitation(
            invitation: OpenGroupInvitation,
            sender: Address,
            sentTimestampMillis: Long,
            expiresInMillis: Long,
            expireStartedAt: Long,
        ): IncomingTextMessage? {
            val body = UpdateMessageData.buildOpenGroupInvitation(
                url = invitation.url ?: return null,
                name = invitation.name ?: return null,
            ).toJSON()

            return IncomingTextMessage(
                message = body,
                sender = sender,
                senderDeviceId = 1,
                sentTimestampMillis = sentTimestampMillis,
                group = null,
                push = true,
                expiresInMillis = expiresInMillis,
                expireStartedAt = expireStartedAt,
                unidentified = false,
                callType = -1,
                hasMention = false,
                isOpenGroupInvitation = true,
                isSecureMessage = false,
            )
        }
    }
}