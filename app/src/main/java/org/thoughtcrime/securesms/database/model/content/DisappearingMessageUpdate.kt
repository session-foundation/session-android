package org.thoughtcrime.securesms.database.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.util.ExpiryMode

typealias ExpiryModeName = String

@Serializable
@SerialName(DisappearingMessageUpdate.TYPE_NAME)
data class DisappearingMessageUpdate(
    @SerialName("expiry_time_seconds")
    val expiryTimeSeconds: Long,

    @SerialName("expiry_mode")
    val expiryModeName: ExpiryModeName,
) : MessageContent {
    val expiryMode: ExpiryMode
        get() = when (expiryModeName) {
            EXPIRY_MODE_AFTER_SENT -> ExpiryMode.AfterSend(expiryTimeSeconds)
            EXPIRY_MODE_AFTER_READ -> ExpiryMode.AfterRead(expiryTimeSeconds)
            EXPIRY_MODE_NONE -> ExpiryMode.NONE
            else -> throw IllegalArgumentException("Unknown expiry mode: $expiryModeName")
        }

    constructor(mode: ExpiryMode) : this(
        expiryTimeSeconds = mode.expirySeconds,
        expiryModeName = when (mode) {
            is ExpiryMode.AfterSend -> EXPIRY_MODE_AFTER_SENT
            is ExpiryMode.AfterRead -> EXPIRY_MODE_AFTER_READ
            ExpiryMode.NONE -> EXPIRY_MODE_NONE
        }
    )


    companion object {
        const val TYPE_NAME = "disappearing_message_update"

        const val EXPIRY_MODE_AFTER_SENT: ExpiryModeName = "after_sent"
        const val EXPIRY_MODE_AFTER_READ: ExpiryModeName = "after_read"
        const val EXPIRY_MODE_NONE: ExpiryModeName = "none"
    }
}
