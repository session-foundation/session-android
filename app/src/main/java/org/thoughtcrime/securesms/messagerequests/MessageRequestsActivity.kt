package org.thoughtcrime.securesms.messagerequests

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityMessageRequestsBinding
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.recipients.displayName
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.push
import javax.inject.Inject

@AndroidEntryPoint
class MessageRequestsActivity : ScreenLockActionBarActivity(), ConversationClickListener {

    private lateinit var binding: ActivityMessageRequestsBinding

    @Inject lateinit var dateUtils: DateUtils

    private val viewModel: MessageRequestsViewModel by viewModels()

    private val adapter: MessageRequestsAdapter by lazy {
        MessageRequestsAdapter(dateUtils = dateUtils, listener = this)
    }

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        binding = ActivityMessageRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter.setHasStableIds(true)
        binding.recyclerView.adapter = adapter

        binding.clearAllMessageRequestsButton.setOnClickListener { deleteAll() }

        binding.root.applySafeInsetsPaddings(
            applyBottom = false,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.threads
                    .collectLatest {
                        adapter.conversations = it
                        updateEmptyState()
                    }
            }
        }
    }

    override fun onConversationClick(thread: ThreadRecord) {
        push(ConversationActivityV2.createIntent(this, thread.recipient.address as Address.Conversable))
    }

    override fun onBlockConversationClick(thread: ThreadRecord) {
        fun doBlock() {
            val recipient = thread.invitingAdminId?.let {
                Address.fromSerialized(it)
            } ?: thread.recipient.address
            viewModel.blockMessageRequest(thread, recipient)
        }

        showSessionDialog {
            title(R.string.block)
            text(Phrase.from(context, R.string.blockDescription)
                .put(NAME_KEY, thread.recipient.displayName())
                .format())
            dangerButton(R.string.block, R.string.AccessibilityId_blockConfirm) {
                doBlock()
            }
            button(R.string.no)
        }
    }

    override fun onDeleteConversationClick(thread: ThreadRecord) {
        fun doDecline() {
            viewModel.deleteMessageRequest(thread)
        }

        showSessionDialog {
            title(R.string.delete)
            text(resources.getString(R.string.messageRequestsContactDelete))
            dangerButton(R.string.delete) { doDecline() }
            button(R.string.cancel)
        }
    }

    private fun updateEmptyState() {
        val threadCount = adapter.itemCount
        binding.emptyStateContainer.isVisible = threadCount == 0
        binding.clearAllMessageRequestsButton.isVisible = threadCount != 0
    }

    private fun deleteAll() {
        showSessionDialog {
            title(resources.getString(R.string.clearAll))
            text(resources.getString(R.string.messageRequestsClearAllExplanation))
            dangerButton(R.string.clear) { viewModel.clearAllMessageRequests() }
            button(R.string.cancel)
        }
    }
}
