package org.session.libsession.messaging.messages

import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.utilities.Address
import org.session.protos.SessionProtos
import org.session.protos.SessionProtos.Content.ExpirationType
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.pro.toProMessageBitSetValue
import org.thoughtcrime.securesms.pro.toProProfileBitSetValue

abstract class Message {
    var id: MessageId? = null // Message ID in the database. Not all messages will be saved to db.
    var threadID: Long? = null
    var sentTimestamp: Long? = null
    var receivedTimestamp: Long? = null
    var recipient: String? = null
    var sender: String? = null
    var isSenderSelf: Boolean = false

    var groupPublicKey: String? = null
    var openGroupServerMessageID: Long? = null
    var serverHash: String? = null
    var specifiedTtl: Long? = null

    var expiryMode: ExpiryMode = ExpiryMode.NONE

    /**
     * The pro features enabled for this message.
     *
     * Note:
     * * When this message is an incoming message, the pro features will only be populated
     * if we can prove that the sender has an active pro subscription.
     *
     * * When this message represents an outgoing message, this property can be populated by
     * application code at their wishes but the actual translating to protobuf onto the wired will
     * be checked against the current user's pro proof, if no active pro subscription is found,
     * the pro features will not be sent in the protobuf messages.
     */
    var proFeatures: Set<ProFeature> = emptySet()

    open val coerceDisappearAfterSendToRead = false

    open val defaultTtl: Long = SnodeMessage.DEFAULT_TTL
    open val ttl: Long get() = specifiedTtl ?: defaultTtl
    open val isSelfSendValid: Boolean = false

    companion object {

        val Message.senderOrSync get() = when(this)  {
            is VisibleMessage -> syncTarget ?: sender!!
            is ExpirationTimerUpdate -> syncTarget ?: sender!!
            else -> sender!!
        }
    }

    open fun isValid(): Boolean =
        sentTimestamp?.let { it > 0 } != false
            && receivedTimestamp?.let { it > 0 } != false
            && sender != null
            && recipient != null

    protected abstract fun buildProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    )

    fun toProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        // First apply common message data
        // * Expiry mode
        builder.expirationTimer = expiryMode.expirySeconds.toInt()
        builder.expirationType = when (expiryMode) {
            is ExpiryMode.AfterSend -> ExpirationType.DELETE_AFTER_SEND
            is ExpiryMode.AfterRead -> ExpirationType.DELETE_AFTER_READ
            else -> ExpirationType.UNKNOWN
        }

        // * Timestamps
        builder.setSigTimestamp(sentTimestamp!!)

        // Pro features
        if (proFeatures.any { it is ProMessageFeature }) {
            builder.proMessageBuilder.setMsgBitset(
                proFeatures.toProMessageBitSetValue()
            )
        }

        if (proFeatures.any { it is ProProfileFeature }) {
            builder.proMessageBuilder.setProfileBitset(
                proFeatures.toProProfileBitSetValue()
            )
        }

        // Then ask the subclasses to build their specific proto
        buildProto(builder, messageDataProvider)
    }

    abstract fun shouldDiscardIfBlocked(): Boolean
}

inline fun <reified M: Message> M.copyExpiration(proto: SessionProtos.Content): M = apply {
    proto.takeIf { it.hasExpirationTimer() }?.expirationTimer?.let { duration ->
        expiryMode = when (proto.expirationType.takeIf { duration > 0 }) {
            ExpirationType.DELETE_AFTER_SEND -> ExpiryMode.AfterSend(duration.toLong())
            ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(duration.toLong())
            else -> ExpiryMode.NONE
        }
    }
}

/**
 * Apply ExpiryMode from the current setting.
 */
inline fun <reified M: Message> M.applyExpiryMode(recipientAddress: Address): M = apply {
    expiryMode = MessagingModuleConfiguration.shared.recipientRepository.getRecipientSync(recipientAddress)
        .expiryMode.coerceSendToRead(coerceDisappearAfterSendToRead)
}
