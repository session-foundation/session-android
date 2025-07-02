package org.session.libsession.utilities

import android.content.Context
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.thoughtcrime.securesms.database.model.MessageId

class SSKEnvironment(
    val typingIndicators: TypingIndicatorsProtocol,
    val readReceiptManager: ReadReceiptManagerProtocol,
    val notificationManager: MessageNotifier,
    val messageExpirationManager: MessageExpirationManagerProtocol,
    val profileUpdateHandler: ProfileUpdateHandler,
) {

    interface TypingIndicatorsProtocol {
        fun didReceiveTypingStartedMessage(context: Context, threadId: Long, author: Address, device: Int)
        fun didReceiveTypingStoppedMessage(context: Context, threadId: Long, author: Address, device: Int, isReplacedByIncomingMessage: Boolean)
        fun didReceiveIncomingMessage(context: Context, threadId: Long, author: Address, device: Int)
    }

    interface ReadReceiptManagerProtocol {
        fun processReadReceipts(context: Context, fromRecipientId: String, sentTimestamps: List<Long>, readTimestamp: Long)
    }

    interface MessageExpirationManagerProtocol {
        fun insertExpirationTimerMessage(message: ExpirationTimerUpdate)

        /**
         * Starts the expiration timer for a message, regardless of it has been sent, read or not.
         *
         * However, the timer will not start if it doesn't have its expiryMode set.
         */
        fun startExpiringNow(id: MessageId)

        fun onMessageSent(message: Message)
        fun onMessageReceived(message: Message)
    }

    companion object {
        @Deprecated("Use Hilt to inject your dependencies instead")
        lateinit var shared: SSKEnvironment

        fun configure(typingIndicators: TypingIndicatorsProtocol,
                      readReceiptManager: ReadReceiptManagerProtocol,
                      notificationManager: MessageNotifier,
                      messageExpirationManager: MessageExpirationManagerProtocol,
                      profileUpdateHandler: ProfileUpdateHandler) {
            if (Companion::shared.isInitialized) { return }
            shared = SSKEnvironment(
                typingIndicators = typingIndicators,
                readReceiptManager = readReceiptManager,
                notificationManager = notificationManager,
                messageExpirationManager = messageExpirationManager,
                profileUpdateHandler = profileUpdateHandler
            )
        }
    }
}
