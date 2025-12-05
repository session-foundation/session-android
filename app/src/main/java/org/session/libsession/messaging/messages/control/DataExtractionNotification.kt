package org.session.libsession.messaging.messages.control

import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.messages.copyExpiration
import org.session.protos.SessionProtos

class DataExtractionNotification() : ControlMessage() {
    var kind: Kind? = null

    override val coerceDisappearAfterSendToRead = true

    sealed class Kind {
        class Screenshot() : Kind()
        class MediaSaved(val timestamp: Long) : Kind()

        val description: String =
            when (this) {
                is Screenshot -> "screenshot"
                is MediaSaved -> "mediaSaved"
            }
    }

    override fun shouldDiscardIfBlocked(): Boolean = true

    companion object {
        const val TAG = "DataExtractionNotification"

        fun fromProto(proto: SessionProtos.Content): DataExtractionNotification? {
            if (!proto.hasDataExtractionNotification()) return null
            val dataExtractionNotification = proto.dataExtractionNotification!!
            val kind: Kind = when(dataExtractionNotification.type) {
                SessionProtos.DataExtractionNotification.Type.SCREENSHOT -> Kind.Screenshot()
                SessionProtos.DataExtractionNotification.Type.MEDIA_SAVED -> {
                    val timestamp = if (dataExtractionNotification.hasTimestamp()) dataExtractionNotification.timestamp else return null
                    Kind.MediaSaved(timestamp)
                }
            }
            return DataExtractionNotification(kind)
                    .copyExpiration(proto)
        }
    }

    constructor(kind: Kind) : this() {
        this.kind = kind
    }

    override fun isValid(): Boolean {
        val kind = kind
        if (!super.isValid() || kind == null) return false
        return when(kind) {
            is Kind.Screenshot -> true
            is Kind.MediaSaved -> kind.timestamp > 0
        }
    }

    override fun buildProto(
        builder: SessionProtos.Content.Builder,
        messageDataProvider: MessageDataProvider
    ) {
        val dataExtractionNotification = builder.dataExtractionNotificationBuilder
        when (val kind = kind!!) {
            is Kind.Screenshot -> dataExtractionNotification.type = SessionProtos.DataExtractionNotification.Type.SCREENSHOT
            is Kind.MediaSaved -> {
                dataExtractionNotification.type = SessionProtos.DataExtractionNotification.Type.MEDIA_SAVED
                dataExtractionNotification.timestamp = kind.timestamp
            }
        }
    }
}
