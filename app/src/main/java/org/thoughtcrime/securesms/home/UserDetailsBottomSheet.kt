package org.thoughtcrime.securesms.home

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentUserDetailsBottomSheetBinding
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.BasicRecipient
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject

@AndroidEntryPoint
class UserDetailsBottomSheet: BottomSheetDialogFragment() {

    @Inject lateinit var threadDb: ThreadDatabase
    @Inject lateinit var recipientRepository: RecipientRepository
    @Inject lateinit var configFactory: ConfigFactory

    private lateinit var binding: FragmentUserDetailsBottomSheetBinding

    private var previousContactNickname: String = ""

    companion object {
        const val ARGUMENT_PUBLIC_KEY = "publicKey"
        const val ARGUMENT_THREAD_ID = "threadId"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val wrappedContext = ContextThemeWrapper(requireActivity(), requireActivity().theme)
        val themedInflater = inflater.cloneInContext(wrappedContext)
        binding = FragmentUserDetailsBottomSheetBinding.inflate(themedInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val publicKey = arguments?.getString(ARGUMENT_PUBLIC_KEY) ?: return dismiss()
        val threadID = arguments?.getLong(ARGUMENT_THREAD_ID) ?: return dismiss()
        val threadRecipient = threadDb.getRecipientForThreadId(threadID)?.let(recipientRepository::getRecipientSync) ?: return dismiss()
        val recipient = recipientRepository.getRecipientSync(Address.fromSerialized(publicKey)) ?: return dismiss()
        with(binding) {
            profilePictureView.publicKey = publicKey
            profilePictureView.update(recipient)
            nameTextViewContainer.visibility = View.VISIBLE
            nameTextViewContainer.setOnClickListener {
                if (recipient.isCommunityInboxRecipient || recipient.isCommunityOutboxRecipient) return@setOnClickListener
                nameTextViewContainer.visibility = View.INVISIBLE
                nameEditTextContainer.visibility = View.VISIBLE
                nicknameEditText.text = null
                nicknameEditText.requestFocus()
                showSoftKeyboard()
            }
            cancelNicknameEditingButton.setOnClickListener {
                nicknameEditText.clearFocus()
                hideSoftKeyboard()
                nameTextViewContainer.visibility = View.VISIBLE
                nameEditTextContainer.visibility = View.INVISIBLE
            }
            saveNicknameButton.setOnClickListener {
                saveNickName(recipient)
            }
            nicknameEditText.setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_DONE -> {
                        saveNickName(recipient)
                        return@setOnEditorActionListener true
                    }
                    else -> return@setOnEditorActionListener false
                }
            }
            nameTextView.text = recipient.displayName

            nameEditIcon.isVisible = recipient.basic is BasicRecipient.Contact

            publicKeyTextView.isVisible = !threadRecipient.isCommunityRecipient
                    && !threadRecipient.isCommunityInboxRecipient
                    && !threadRecipient.isCommunityOutboxRecipient
            messageButton.isVisible = !threadRecipient.isCommunityRecipient || IdPrefix.fromValue(publicKey)?.isBlinded() == true
            publicKeyTextView.text = publicKey
            publicKeyTextView.setOnLongClickListener {
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Account ID", publicKey)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT)
                    .show()
                true
            }
            messageButton.setOnClickListener {
                startActivity(
                    ConversationActivityV2.createIntent(
                        requireContext(),
                        address = recipient.address,
                        fromGroupThreadId = threadID
                    )
                )
                dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setDimAmount(0.6f)
    }

    fun saveNickName(recipient: Recipient) = with(binding) {
        nicknameEditText.clearFocus()
        hideSoftKeyboard()
        nameTextViewContainer.visibility = View.VISIBLE
        nameEditTextContainer.visibility = View.INVISIBLE
        val newNickName: String?
        if (nicknameEditText.text.isNotEmpty() && nicknameEditText.text.trim().length != 0) {
            newNickName = nicknameEditText.text.toString()
        }
        else { newNickName = previousContactNickname }

        if (AccountId.fromStringOrNull(recipient.address.address)?.prefix == IdPrefix.STANDARD) {
            configFactory.withMutableUserConfigs { configs ->
                configs.contacts.upsertContact(recipient.address.address) {
                    this.nickname = newNickName
                }
            }
        }

        nameTextView.text = newNickName

        (parentFragment as? UserDetailsBottomSheetCallback)
            ?: (requireActivity() as? UserDetailsBottomSheetCallback)?.onNicknameSaved()
    }

    @SuppressLint("ServiceCast")
    fun showSoftKeyboard() {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.nicknameEditText, 0)

        // Keep track of the original nickname to re-use if an empty / blank nickname is entered
        previousContactNickname = binding.nameTextView.text.toString()
    }

    fun hideSoftKeyboard() {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.nicknameEditText.windowToken, 0)
    }

    interface UserDetailsBottomSheetCallback {
        fun onNicknameSaved()
    }
}