package org.thoughtcrime.securesms.mediasend.compose

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.thoughtcrime.securesms.conversation.v2.utilities.AttachmentManager
import org.thoughtcrime.securesms.mediasend.MediaFolder
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import org.thoughtcrime.securesms.ui.setThemedContent

@AndroidEntryPoint
class MediaPickerFolderComposeFragment : Fragment() {

    private val viewModel: MediaSendViewModel by activityViewModels()

    private var recipientName: String? = null
    private var controller: Controller? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        controller = activity as? Controller
            ?: throw IllegalStateException("Parent activity must implement controller class.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recipientName = requireArguments().getString(KEY_RECIPIENT_NAME)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onFolderPickerStarted()
        viewModel.refreshPhotoAccessUi()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setThemedContent {
                val ctx = LocalContext.current
                // Same title as the old toolbar
                val title = remember(recipientName) {
                    Phrase.from(ctx, R.string.attachmentsSendTo)
                        .put(StringSubstitutionConstants.NAME_KEY, recipientName ?: "")
                        .format()
                        .toString()
                }

                MediaPickerFolderScreen(
                    viewModel = viewModel,
                    title = title,
                    handleBack = {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    },
                    onFolderClick = { folder ->
                        controller?.onFolderSelected(folder)
                    },
                    manageMediaAccess = ::manageMediaAccess
                )
            }
        }
    }

    fun manageMediaAccess() {
        AttachmentManager.managePhotoAccess(requireActivity()) {
            viewModel.refreshFolders()
        }
    }

    companion object {
        private const val KEY_RECIPIENT_NAME = "recipient_name"

        fun newInstance(recipient: Recipient): MediaPickerFolderComposeFragment {
            return MediaPickerFolderComposeFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_RECIPIENT_NAME, recipient.displayName(false))
                }
            }
        }
    }

    interface Controller {
        fun onFolderSelected(folder: MediaFolder)
    }
}