package org.session.libsession.messaging.messages

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
