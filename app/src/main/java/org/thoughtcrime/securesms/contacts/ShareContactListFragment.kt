package org.thoughtcrime.securesms.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.databinding.ShareContactListFragmentBinding
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.repository.ConversationRepository
import javax.inject.Inject

@AndroidEntryPoint
class ShareContactListFragment : Fragment(), LoaderManager.LoaderCallbacks<List<Recipient>>, ContactClickListener {
    private lateinit var binding: ShareContactListFragmentBinding
    private var cursorFilter: String? = null
    var onContactSelectedListener: OnContactSelectedListener? = null

    @Inject
    lateinit var deprecationManager: LegacyGroupDeprecationManager

    @Inject
    lateinit var storage: StorageProtocol

    @Inject
    lateinit var conversationRepository: ConversationRepository

    private val multiSelect: Boolean by lazy {
        requireActivity().intent.getBooleanExtra(MULTI_SELECT, false)
    }

    private val listAdapter by lazy {
        val result = ContactSelectionListAdapter(requireActivity(), multiSelect)
        result.glide = Glide.with(this)
        result.contactClickListener = this
        result
    }

    companion object {
        @JvmField val MULTI_SELECT = "multi_select"
        @JvmField val REFRESHABLE = "refreshable"
    }

    interface OnContactSelectedListener {
        fun onContactSelected(number: String)
        fun onContactDeselected(number: String?)
    }

    override fun onStart() {
        super.onStart()
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ShareContactListFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = listAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bottomInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom

            binding.recyclerView.updatePadding(bottom = bottomInsets)

            // There shouldn't be anything else needing the insets so we'll consume all of them
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onStop() {
        super.onStop()
        LoaderManager.getInstance(this).destroyLoader(0)
    }

    fun setQueryFilter(filter: String?) {
        cursorFilter = filter
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    fun resetQueryFilter() {
        setQueryFilter(null)
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<List<Recipient>> {
        return ShareContactListLoader(
            context = requireActivity(),
            filter = cursorFilter,
            deprecationManager = deprecationManager,
            storage = storage,
            repo = conversationRepository,
        )
    }

    override fun onLoadFinished(loader: Loader<List<Recipient>>, items: List<Recipient>) {
        update(items)
    }

    override fun onLoaderReset(loader: Loader<List<Recipient>>) {
        update(listOf())
    }

    private fun update(items: List<Recipient>) {
        if (activity?.isDestroyed == true) {
            Log.e(ShareContactListFragment::class.java.name,
                    "Received a loader callback after the fragment was detached from the activity.",
                    IllegalStateException())
            return
        }
        listAdapter.items = items
        binding.loader.visibility = View.GONE
        binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyStateContainer.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onContactClick(contact: Recipient) {
        listAdapter.onContactClick(contact)
    }

    override fun onContactSelected(contact: Recipient) {
        onContactSelectedListener?.onContactSelected(contact.address.toString())
    }

    override fun onContactDeselected(contact: Recipient) {
        onContactSelectedListener?.onContactDeselected(contact.address.toString())
    }
}
