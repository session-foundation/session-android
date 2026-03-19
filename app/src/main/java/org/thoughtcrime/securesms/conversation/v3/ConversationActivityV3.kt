package org.thoughtcrime.securesms.conversation.v3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.model.MessageId

class ConversationActivityV3 : FullComposeScreenLockActivity() {

    private var pendingScrollMessageId: MessageId? by mutableStateOf(null)

    companion object {
        // Extras
        const val ADDRESS = "address"
        private const val SCROLL_MESSAGE_ID = "scroll_message_id"
        private const val EXTRA_START_DESTINATION = "conversation_start_destination"

        fun createIntent(
            context: Context,
            address: Address.Conversable,
            // If provided, this will scroll to the message with the given message id
            scrollToMessage: MessageId? = null
        ): Intent {
            return Intent(context, ConversationActivityV3::class.java).apply {
                putExtra(ADDRESS, address)
                scrollToMessage?.let {
                    putExtra(SCROLL_MESSAGE_ID, it)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // calling before onCreate to make sure the composeContent has the right value straight away
        pendingScrollMessageId = extractScrollMessageId(intent)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingScrollMessageId = extractScrollMessageId(intent)
    }

    @Composable
    override fun ComposeContent() {
        val initialAddress: Address.Conversable? = IntentCompat.getParcelableExtra(intent, ADDRESS, Address.Conversable::class.java)
        if (initialAddress == null) {
            LaunchedEffect(Unit) {
                finish()
            }
            return
        }

        val startDestination = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_START_DESTINATION,
            ConversationV3Destination::class.java
        ) ?: ConversationV3Destination.RouteConversation(initialAddress)

        ConversationV3NavHost(
            initialAddress = initialAddress,
            startDestination = startDestination,
            pendingScrollMessageId = pendingScrollMessageId,
            onPendingScrollConsumed = {
                pendingScrollMessageId = null
            },
            switchConvoVersion = { address ->
                startActivity(ConversationActivityV2.createIntent(this, address = address))
                finish()
            },
            onBack = this::finish
        )
    }

    private fun extractScrollMessageId(intent: Intent): MessageId? {
        return IntentCompat.getParcelableExtra(intent, SCROLL_MESSAGE_ID, MessageId::class.java)
    }
}
