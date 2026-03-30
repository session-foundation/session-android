package org.session.libsession.utilities

import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate

interface MessageExpirationManagerProtocol {
    fun insertExpirationTimerMessage(message: ExpirationTimerUpdate)

    fun onMessageSent(message: Message)
    fun onMessageReceived(message: Message)
}