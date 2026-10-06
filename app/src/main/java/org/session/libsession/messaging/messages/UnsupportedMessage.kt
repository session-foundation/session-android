package org.session.libsession.messaging.messages

import org.session.libsession.database.MessageDataProvider
import org.session.protos.SessionProtos

/**
 * A swarm message this client positively identified as something it can't process. There are only
 * two ways to get one:
 * - [Kind.NEWER_FORMAT]: the raw one-to-one swarm data starts with `0x00`, identified without
 *   attempting decryption. It has no sender and no conversation.
 * - [Kind.UNKNOWN_TYPE]: the message decrypted (so the sender is authenticated) but its `Content`
 *   holds a type this client doesn't know.
 *
 * Receive-only: it is never sent.
 */
class UnsupportedMessage(
    val kind: Kind,
    val placement: Placement,
    val rawData: ByteArray,
    val swarmPublicKey: String,
    val namespace: Int,
    val serverTimestampMs: Long,
    val serverExpiryMs: Long?,
    val syncTarget: String?,
) : Message() {

    /** The values are stored in the shared `unsupported_message.kind` column, so must not change. */
    enum class Kind(val dbValue: String) {
        NEWER_FORMAT("newerFormat"),
        UNKNOWN_TYPE("unknownType");

        companion object {
            fun fromDbValue(value: String): Kind? = entries.firstOrNull { it.dbValue == value }
        }
    }

    enum class Placement {
        /** Nowhere to show it (a newer-format message, or our own sync without a target), only retained */
        NONE,
        INCOMING,
        OUTGOING,
    }

    override val isSelfSendValid: Boolean get() = true

    override fun isValid(): Boolean = super.isValid() && rawData.isNotEmpty() && !serverHash.isNullOrEmpty()

    override fun shouldDiscardIfBlocked(): Boolean = true

    override fun buildProto(builder: SessionProtos.Content.Builder, messageDataProvider: MessageDataProvider) {
        throw UnsupportedOperationException("An UnsupportedMessage is never sent")
    }
}
