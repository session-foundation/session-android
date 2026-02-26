package org.session.libsession.utilities

interface TypingIndicatorsProtocol {
    fun didReceiveTypingStartedMessage(threadId: Long, author: Address, device: Int)
    fun didReceiveTypingStoppedMessage(
        threadId: Long,
        author: Address,
        device: Int,
        isReplacedByIncomingMessage: Boolean
    )
    fun didReceiveIncomingMessage(threadId: Long, author: Address, device: Int)
}