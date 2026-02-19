package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.protos.SessionProtos

class ReadReceipt() : ControlMessage() {
    var timestamps: List<Long>? = null

    override fun isValid(): Boolean {
        if (!super.isValid()) return false
        val timestamps = timestamps ?: return false
        if (timestamps.isNotEmpty()) { return true }
        return false
    }

    override fun shouldDiscardIfBlocked(): Boolean = true

    companion object {
        const val TAG = "ReadReceipt"

        fun fromProto(proto: SessionProtos.Content): ReadReceipt? {
            val receiptProto = if (proto.hasReceiptMessage()) proto.receiptMessage else return null
            if (receiptProto.type != SessionProtos.ReceiptMessage.Type.READ) return null
            val timestamps = receiptProto.timestampList
            if (timestamps.isEmpty()) return null
            return ReadReceipt(timestamps = timestamps)
                    .copyExpiration(proto)
        }
    }

    constructor(timestamps: List<Long>?) : this() {
        this.timestamps = timestamps
    }

    override fun buildProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        builder
            .receiptMessageBuilder
            .setType(SessionProtos.ReceiptMessage.Type.READ)
            .addAllTimestamp(requireNotNull(timestamps) {
                "Timestamps is null"
            })
    }
}