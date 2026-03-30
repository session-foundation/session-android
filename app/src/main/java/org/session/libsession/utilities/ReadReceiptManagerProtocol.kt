package org.session.libsession.utilities

interface ReadReceiptManagerProtocol {
    fun processReadReceipts(
        fromRecipientId: String,
        sentTimestamps: List<Long>,
        readTimestamp: Long
    )
}