package org.thoughtcrime.securesms.conversation.v3

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.isBlinded
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.database.model.MessageId

class ConversationActivityV3 : FullComposeScreenLockActivity() {

    companion object {
        // Extras
        private const val ADDRESS = "address"
        private const val SCROLL_MESSAGE_ID = "scroll_message_id"
        private const val CONVERSATION_SCROLL_STATE = "conversation_scroll_state"
        private const val EXTRA_START_DESTINATION = "conversation_start_destination"

        fun createIntent(
            context: Context,
            address: Address.Conversable,
            // If provided, this will scroll to the message with the given message id
            scrollToMessage: MessageId? = null
        ): Intent {
            require(!address.isBlinded) {
                "Cannot create a conversation for a blinded address. Use a \"Community inbox\" address instead."
            }

            return Intent(context, ConversationActivityV3::class.java).apply {
                putExtra(ADDRESS, address)
                scrollToMessage?.let {
                    putExtra(SCROLL_MESSAGE_ID, it)
                }
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        val startDestination = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_START_DESTINATION,
            ConversationV3Destination::class.java
        ) ?:  ConversationV3Destination.RouteConversation

        ConversationV3NavHost(
            //todo convov3 v2 convo would go back home if address is null - update this
            address = requireNotNull(IntentCompat.getParcelableExtra(intent, ADDRESS, Address.Conversable::class.java)) {
                "ConversationV3Activity requires an Address to be passed in the intent."
            },
            startDestination = startDestination,
            onBack = this::finish
        )
    }
}
