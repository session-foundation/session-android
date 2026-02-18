package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.copyExpiration
import org.session.protos.SessionProtos
import org.session.protos.SessionProtos.DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE

/** In the case of a sync message, the public key of the person the message was targeted at.
 *
 * **Note:** `nil` if this isn't a sync message.
 */
data class ExpirationTimerUpdate @JvmOverloads constructor(var syncTarget: String? = null, val isGroup: Boolean = false) : ControlMessage() {
    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean = true

    companion object {
        const val TAG = "ExpirationTimerUpdate"
        private val storage = MessagingModuleConfiguration.shared.storage

        fun fromProto(proto: SessionProtos.Content, isGroup: Boolean): ExpirationTimerUpdate? =
            proto.dataMessage?.takeIf { it.flags and EXPIRATION_TIMER_UPDATE_VALUE != 0 }?.run {
                ExpirationTimerUpdate(takeIf { hasSyncTarget() }?.syncTarget, isGroup).copyExpiration(proto)
            }
    }

    override fun buildProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder.dataMessageBuilder
            .setFlags(EXPIRATION_TIMER_UPDATE_VALUE)
            .also { builder ->
                // Sync target
                syncTarget?.let { builder.syncTarget = it }
            }
    }
}
