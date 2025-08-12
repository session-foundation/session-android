package org.session.libsession.utilities

import android.content.Context
import dagger.Lazy
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.utilities.recipients.Recipient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SSKEnvironment @Inject constructor(
    val typingIndicators: TypingIndicatorsProtocol,
    val profileManager: ProfileManagerProtocol,
    val messageExpirationManager: MessageExpirationManagerProtocol
) {

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

    interface ReadReceiptManagerProtocol {
        fun processReadReceipts(
            fromRecipientId: String,
            sentTimestamps: List<Long>,
            readTimestamp: Long
        )
    }

    interface ProfileManagerProtocol {
        companion object {
            const val NAME_PADDED_LENGTH = 100
        }

        fun setNickname(context: Context, recipient: Recipient, nickname: String?)
        fun setName(context: Context, recipient: Recipient, name: String?)
        fun setProfilePicture(context: Context, recipient: Recipient, profilePictureURL: String?, profileKey: ByteArray?)
        fun contactUpdatedInternal(contact: Contact): String?
    }

    interface MessageExpirationManagerProtocol {
        fun insertExpirationTimerMessage(message: ExpirationTimerUpdate)

        fun onMessageSent(message: Message)
        fun onMessageReceived(message: Message)
    }

    companion object {
        lateinit var sharedLazy: Lazy<SSKEnvironment>

        @Deprecated("Use Hilt to inject your dependencies instead")
        val shared: SSKEnvironment get() = sharedLazy.get()
    }
}
