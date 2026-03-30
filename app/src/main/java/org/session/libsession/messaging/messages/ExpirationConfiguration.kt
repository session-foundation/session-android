package org.session.libsession.messaging.messages

import network.loki.messenger.libsession_util.util.ExpiryMode

data class ExpirationConfiguration(
    val threadId: Long = -1,
    val expiryMode: ExpiryMode = ExpiryMode.NONE,
    val updatedTimestampMs: Long = 0
) {
    val isEnabled get() = expiryMode.expirySeconds > 0
}

data class ExpirationDatabaseMetadata(
    val threadId: Long = -1,
    val updatedTimestampMs: Long
)
