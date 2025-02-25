package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentCreateGroupBinding
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.groupSizeLimit
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.contacts.SelectContactsAdapter
import org.thoughtcrime.securesms.conversation.start.StartConversationDelegate
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.keyboard.emoji.KeyboardPageSearchView
import com.bumptech.glide.Glide
import org.thoughtcrime.securesms.util.fadeIn
import org.thoughtcrime.securesms.util.fadeOut
import javax.inject.Inject

@AndroidEntryPoint
class CreateGroupFragment : Fragment() {

    @Inject
    lateinit var device: Device

    private lateinit var binding: FragmentCreateGroupBinding
    private val viewModel: CreateGroupViewModel by viewModels()

    lateinit var delegate: StartConversationDelegate

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCreateGroupBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = SelectContactsAdapter(requireContext(), Glide.with(requireContext()))
        binding.backButton.setOnClickListener { delegate.onDialogBackPressed() }
        binding.closeButton.setOnClickListener { delegate.onDialogClosePressed() }
        binding.contactSearch.callbacks = object : KeyboardPageSearchView.Callbacks {
            override fun onQueryChanged(query: String) {
                adapter.members = viewModel.filter(query).map { it.address.serialize() }
            }
        }
        binding.createNewPrivateChatButton.setOnClickListener { delegate.onNewMessageSelected() }
        binding.recyclerView.adapter = adapter
        val divider = ContextCompat.getDrawable(requireActivity(), R.drawable.conversation_menu_divider)!!.let {
            DividerItemDecoration(requireActivity(), RecyclerView.VERTICAL).apply {
                setDrawable(it)
            }
        }
        binding.recyclerView.addItemDecoration(divider)
        var isLoading = false
        binding.createClosedGroupButton.setOnClickListener {
            if (isLoading) return@setOnClickListener
            val name = binding.nameEditText.text.trim()
            if (name.isEmpty()) {
                return@setOnClickListener Toast.makeText(context, R.string.groupNameEnterPlease, Toast.LENGTH_LONG).show()
            }

            // Limit the group name length if it exceeds the limit
            if (name.length > resources.getInteger(R.integer.max_group_and_community_name_length_chars)) {
                return@setOnClickListener Toast.makeText(context, R.string.groupNameEnterShorter, Toast.LENGTH_LONG).show()
            }

            val selectedMembers = adapter.selectedMembers
            if (selectedMembers.isEmpty()) {
                return@setOnClickListener Toast.makeText(context, R.string.groupCreateErrorNoMembers, Toast.LENGTH_LONG).show()
            }
            if (selectedMembers.count() >= groupSizeLimit) { // Minus one because we're going to include self later
                return@setOnClickListener Toast.makeText(context, R.string.groupAddMemberMaximum, Toast.LENGTH_LONG).show()
            }
            val userPublicKey = TextSecurePreferences.getLocalNumber(requireContext())!!
            isLoading = true
            binding.loaderContainer.fadeIn()
            MessageSender.createClosedGroup(device, name.toString(), selectedMembers + setOf( userPublicKey )).successUi { groupID ->
                binding.loaderContainer.fadeOut()
                isLoading = false
                val threadID = DatabaseComponent.get(requireContext()).threadDatabase().getOrCreateThreadIdFor(Recipient.from(requireContext(), Address.fromSerialized(groupID), false))
                openConversationActivity(
                    requireContext(),
                    threadID,
                    Recipient.from(requireContext(), Address.fromSerialized(groupID), false)
                )
                delegate.onDialogClosePressed()
            }.failUi {
                binding.loaderContainer.fadeOut()
                isLoading = false
                Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
        }
        binding.mainContentGroup.isVisible = !viewModel.recipients.value.isNullOrEmpty()
        binding.emptyStateGroup.isVisible = viewModel.recipients.value.isNullOrEmpty()
        viewModel.recipients.observe(viewLifecycleOwner) { recipients ->
            adapter.members = recipients.map { it.address.serialize() }
        }
    }

    private fun openConversationActivity(context: Context, threadId: Long, recipient: Recipient) {
        val intent = Intent(context, ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.THREAD_ID, threadId)
        intent.putExtra(ConversationActivityV2.ADDRESS, recipient.address)
        context.startActivity(intent)
    }

}