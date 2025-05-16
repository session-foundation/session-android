package org.thoughtcrime.securesms.database

import org.thoughtcrime.securesms.database.model.MessageId

data class ExpirationInfo(
    val id: MessageId,
    val timestamp: Long,
    val expiresIn: Long,
    val expireStarted: Long,
) {
    private fun isDisappearAfterSend() = timestamp == expireStarted
    fun isDisappearAfterRead() = expiresIn > 0 && !isDisappearAfterSend()
}
