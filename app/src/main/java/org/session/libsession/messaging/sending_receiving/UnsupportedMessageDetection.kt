package org.session.libsession.messaging.sending_receiving

import org.session.libsession.messaging.messages.UnsupportedMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix

/**
 * The decisions behind [UnsupportedMessage], kept free of libsession and database access so they
 * can be unit tested (libsession's native library can't be loaded in JVM tests).
 *
 * The table and rules here are shared with the iOS and Desktop clients, so a later client (or a
 * database import) can treat all three the same way.
 */
object UnsupportedMessageDetection {
    /**
     * The highest top-level `Content` field number ever assigned, including retired numbers and
     * metadata such as `msgId = 18` which newer clients add alongside a type we do know. Comparing
     * against this bound rather than our own schema stops that metadata from looking like a new type.
     */
    const val HIGHEST_KNOWN_CONTENT_FIELD_NUMBER = 18

    /**
     * No v1 one-to-one message can start with a zero byte (it's protobuf, and field number 0 is
     * invalid), which is exactly why the newer wire format starts with one.
     *
     * Only meaningful for the one-to-one namespace: other namespaces are encrypted with symmetric
     * keys so their data can start with any byte.
     */
    fun isNewerFormat(rawData: ByteArray): Boolean = rawData.isNotEmpty() && rawData[0] == 0.toByte()

    /**
     * Returns the field numbers of the top-level fields of a serialised protobuf message, or null if
     * the data isn't well-formed.
     *
     * Hand-written rather than relying on the protobuf library's unknown-field set so the result
     * doesn't depend on how (or whether) the generated classes retain unknown fields.
     */
    fun topLevelFieldNumbers(data: ByteArray): List<Int>? {
        var index = 0
        val result = mutableListOf<Int>()

        fun readVarint(): Long? {
            var value = 0L
            var shift = 0

            while (index < data.size && shift < 64) {
                val byte = data[index].toInt() and 0xFF
                index++
                value = value or ((byte and 0x7F).toLong() shl shift)

                if (byte and 0x80 == 0) return value

                shift += 7
            }

            return null
        }

        while (index < data.size) {
            val key = readVarint() ?: return null
            val fieldNumber = key ushr 3

            if (fieldNumber <= 0 || fieldNumber > Int.MAX_VALUE) return null

            when ((key and 0x7).toInt()) {
                0 -> readVarint() ?: return null
                1 -> index += 8
                2 -> {
                    val length = readVarint() ?: return null
                    if (length < 0 || length > (data.size - index)) return null
                    index += length.toInt()
                }
                5 -> index += 4
                // Groups are deprecated and unused by Session, so anything else is malformed
                else -> return null
            }

            if (index > data.size) return null

            result += fieldNumber.toInt()
        }

        return result
    }

    fun containsUnknownContentType(contentPlaintext: ByteArray): Boolean =
        topLevelFieldNumbers(contentPlaintext).orEmpty().any { it > HIGHEST_KNOWN_CONTENT_FIELD_NUMBER }

    /**
     * Positive evidence of a type added after this client was built. Requiring known content to also
     * be invalid matters for sync copies: the sender adds `dataMessage.syncTarget` to a sync copy
     * whatever its type, so a newer type arrives as an otherwise-empty (and invalid) data message.
     *
     * [hasValidKnownContent] is only evaluated once an unknown field is found, which is rare, so
     * ordinary messages aren't turned into a message twice.
     */
    inline fun isUnknownType(contentPlaintext: ByteArray, hasValidKnownContent: () -> Boolean): Boolean =
        containsUnknownContentType(contentPlaintext) && !hasValidKnownContent()

    /**
     * @return the placement and, for an outgoing one-to-one sync, the conversation it belongs in.
     */
    fun placement(
        isGroup: Boolean,
        isSenderSelf: Boolean,
        syncTarget: String?,
    ): Pair<UnsupportedMessage.Placement, String?> = when {
        isGroup -> (if (isSenderSelf) UnsupportedMessage.Placement.OUTGOING else UnsupportedMessage.Placement.INCOMING) to null
        !isSenderSelf -> UnsupportedMessage.Placement.INCOMING to null
        else -> syncTarget
            ?.takeIf { AccountId.fromStringOrNull(it)?.prefix == IdPrefix.STANDARD }
            ?.let { UnsupportedMessage.Placement.OUTGOING to it }
            ?: (UnsupportedMessage.Placement.NONE to null)
    }

    /**
     * When a retained message has no placeholder there is nothing to own its expiry, so work out when
     * the record itself should go.
     *
     * A disappear-after-send setting is readable from an unknown type. Disappear-after-read can never
     * start because the message is never shown, so it falls to the swarm expiry, which only signals a
     * disappearing message when it's shorter than the default TTL (after-send senders store with a
     * TTL matching their timer; everything else gets the default and is kept for the importer).
     */
    fun retainedExpiryMs(
        afterSendStartedAtMs: Long?,
        afterSendDurationMs: Long?,
        serverTimestampMs: Long,
        serverExpiryMs: Long?,
        defaultTtlMs: Long,
    ): Long? {
        if (afterSendStartedAtMs != null && afterSendDurationMs != null && afterSendDurationMs > 0) {
            return afterSendStartedAtMs + afterSendDurationMs
        }

        return serverExpiryMs?.takeIf { (it - serverTimestampMs) < defaultTtlMs }
    }
}
