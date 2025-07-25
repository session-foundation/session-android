package org.thoughtcrime.securesms.messagerequests

import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityMessageRequestsBinding
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.push

@AndroidEntryPoint
class MessageRequestsActivity : ScreenLockActionBarActivity(), ConversationClickListener, LoaderManager.LoaderCallbacks<Cursor> {

    private lateinit var binding: ActivityMessageRequestsBinding
    private lateinit var glide: RequestManager

    @Inject lateinit var threadDb: ThreadDatabase
    @Inject lateinit var dateUtils: DateUtils

    private val viewModel: MessageRequestsViewModel by viewModels()

    private val adapter: MessageRequestsAdapter by lazy {
        MessageRequestsAdapter(context = this, cursor = null, dateUtils = dateUtils, listener = this)
    }

    override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        binding = ActivityMessageRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        glide = Glide.with(this)

        adapter.setHasStableIds(true)
        adapter.glide = glide
        binding.recyclerView.adapter = adapter

        binding.clearAllMessageRequestsButton.setOnClickListener { deleteAll() }

        binding.root.applySafeInsetsPaddings(
            applyBottom = false,
        )
    }

    override fun onResume() {
        super.onResume()
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    override fun onCreateLoader(id: Int, bundle: Bundle?): Loader<Cursor> {
        return MessageRequestsLoader(this@MessageRequestsActivity)
    }

    override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
        adapter.changeCursor(cursor)
        updateEmptyState()
    }

    override fun onLoaderReset(cursor: Loader<Cursor>) {
        adapter.changeCursor(null)
    }

    override fun onConversationClick(thread: ThreadRecord) {
        val intent = Intent(this, ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.THREAD_ID, thread.threadId)
        push(intent)
    }

    override fun onBlockConversationClick(thread: ThreadRecord) {
        fun doBlock() {
            val recipient = thread.invitingAdminId?.let {
                Recipient.from(this, Address.fromSerialized(it), false)
            } ?: thread.recipient
            viewModel.blockMessageRequest(thread, recipient)
            LoaderManager.getInstance(this).restartLoader(0, null, this)
        }

        showSessionDialog {
            title(R.string.block)
            text(Phrase.from(context, R.string.blockDescription)
                .put(NAME_KEY, thread.recipient.name)
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
            LoaderManager.getInstance(this).restartLoader(0, null, this)
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
        fun doDeleteAllAndBlock() {
            viewModel.clearAllMessageRequests(false)
            LoaderManager.getInstance(this).restartLoader(0, null, this)
        }

        showSessionDialog {
            title(resources.getString(R.string.clearAll))
            text(resources.getString(R.string.messageRequestsClearAllExplanation))
            dangerButton(R.string.clear) { doDeleteAllAndBlock() }
            button(R.string.cancel)
        }
    }
}
