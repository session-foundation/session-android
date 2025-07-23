package org.thoughtcrime.securesms.database.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UnknownMessageContent(
    @SerialName(MessageContent.DISCRIMINATOR)
    val type: String,
) : MessageContent