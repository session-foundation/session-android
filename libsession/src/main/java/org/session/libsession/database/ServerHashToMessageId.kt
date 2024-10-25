package org.session.libsession.database

data class ServerHashToMessageId(
    val serverHash: String,
    /**
     * This will only be the "sender" when the message is incoming.
     */
    val sender: String,
    val messageId: Long,
    val isSms: Boolean,
    val isOutgoing: Boolean,
)