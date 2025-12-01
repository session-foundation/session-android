package org.thoughtcrime.securesms.database.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.protos.SessionProtos
import org.thoughtcrime.securesms.util.ProtobufEnumSerializer


@Serializable
@SerialName(DisappearingMessageUpdate.TYPE_NAME)
data class DisappearingMessageUpdate(
    @SerialName(KEY_EXPIRY_TIME_SECONDS)
    val expiryTimeSeconds: Long,

    @SerialName(KEY_EXPIRY_TYPE)
    @Serializable(with = ExpirationTypeSerializer::class)
    val expiryType: SessionProtos.Content.ExpirationType,
) : MessageContent {
    val expiryMode: ExpiryMode
        get() = when (expiryType) {
            SessionProtos.Content.ExpirationType.DELETE_AFTER_SEND -> ExpiryMode.AfterSend(expiryTimeSeconds)
            SessionProtos.Content.ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(expiryTimeSeconds)
            else -> ExpiryMode.NONE
        }

    constructor(mode: ExpiryMode) : this(
        expiryTimeSeconds = mode.expirySeconds,
        expiryType = when (mode) {
            is ExpiryMode.AfterSend -> SessionProtos.Content.ExpirationType.DELETE_AFTER_SEND
            is ExpiryMode.AfterRead -> SessionProtos.Content.ExpirationType.DELETE_AFTER_READ
            ExpiryMode.NONE -> SessionProtos.Content.ExpirationType.UNKNOWN
        }
    )

    class ExpirationTypeSerializer : ProtobufEnumSerializer<SessionProtos.Content.ExpirationType>() {
        override fun fromNumber(number: Int): SessionProtos.Content.ExpirationType
            = SessionProtos.Content.ExpirationType.forNumber(number) ?: SessionProtos.Content.ExpirationType.UNKNOWN
    }

    companion object {
        const val TYPE_NAME = "disappearing_message_update"

        const val KEY_EXPIRY_TIME_SECONDS = "expiry_time_seconds"
        const val KEY_EXPIRY_TYPE = "expiry_type"

        // These constants map to SessionProtos.Content.ExpirationType but given we want to use
        // a constants it's impossible to use the enum directly. Luckily the values aren't supposed
        // to change so we can safely use these constants.
        const val EXPIRY_MODE_AFTER_SENT = 2
        const val EXPIRY_MODE_AFTER_READ = 1
        const val EXPIRY_MODE_NONE = 0
    }
}
