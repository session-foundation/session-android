package org.session.libsession.messaging.messages

import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.utilities.Address
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.Content.ExpirationType
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

    abstract fun toProto(): SignalServiceProtos.Content?

    abstract fun shouldDiscardIfBlocked(): Boolean

    fun SignalServiceProtos.Content.Builder.applyExpiryMode() = apply {
        expirationTimerSeconds = expiryMode.expirySeconds.toInt()
        expirationType = when (expiryMode) {
            is ExpiryMode.AfterSend -> ExpirationType.DELETE_AFTER_SEND
            is ExpiryMode.AfterRead -> ExpirationType.DELETE_AFTER_READ
            else -> ExpirationType.UNKNOWN
        }
    }
}

inline fun <reified M: Message> M.copyExpiration(proto: SignalServiceProtos.Content): M = apply {
    (proto.takeIf { it.hasExpirationTimerSeconds() }?.expirationTimerSeconds ?: proto.dataMessage?.expireTimerSeconds)?.let { duration ->
        expiryMode = when (proto.expirationType.takeIf { duration > 0 }) {
            ExpirationType.DELETE_AFTER_SEND -> ExpiryMode.AfterSend(duration.toLong())
            ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(duration.toLong())
            else -> ExpiryMode.NONE
        }
    }
}

fun SignalServiceProtos.Content.expiryMode(): ExpiryMode =
    (takeIf { it.hasExpirationTimerSeconds() }?.expirationTimerSeconds ?: dataMessage?.expireTimerSeconds)?.let { duration ->
        when (expirationType.takeIf { duration > 0 }) {
            ExpirationType.DELETE_AFTER_SEND -> ExpiryMode.AfterSend(duration.toLong())
            ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(duration.toLong())
            else -> ExpiryMode.NONE
        }
    } ?: ExpiryMode.NONE

/**
 * Apply ExpiryMode from the current setting.
 */
inline fun <reified M: Message> M.applyExpiryMode(recipientAddress: Address): M = apply {
    expiryMode = MessagingModuleConfiguration.shared.recipientRepository.getRecipientSync(recipientAddress)
        ?.expiryMode?.coerceSendToRead(coerceDisappearAfterSendToRead)
        ?: ExpiryMode.NONE
}
