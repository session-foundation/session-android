package org.thoughtcrime.securesms.mediasend.compose

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import org.thoughtcrime.securesms.ui.setThemedContent

@AndroidEntryPoint
class MediaPickerItemComposeFragment : Fragment() {

    private val viewModel: MediaSendViewModel by activityViewModels()

    private var controller: Controller? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        controller = activity as? Controller
            ?: throw IllegalStateException("Parent activity must implement controller class.")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val bucketId = requireArguments().getString(ARG_BUCKET_ID)!!
        val title = requireArguments().getString(ARG_TITLE)!!

        return ComposeView(requireContext()).apply {
            setThemedContent {
                MediaPickerItemScreen(
                    viewModel = viewModel,
                    bucketId = bucketId,
                    title = title,
                    onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                    onMediaSelected = { media ->
                        // Exact same path as old fragment -> Activity
                        controller?.onMediaSelected(media)
                    }
                )
            }
        }
    }

    companion object {
        private const val ARG_BUCKET_ID = "bucket_id"
        private const val ARG_TITLE = "title"
        private const val ARG_MAX_SELECTION = "max_selection"

        @JvmStatic
        fun newInstance(bucketId: String, title: String) =
            MediaPickerItemComposeFragment().apply {
                arguments = bundleOf(
                    ARG_BUCKET_ID to bucketId,
                    ARG_TITLE to title,
                )
            }
    }

    interface Controller {
        fun onMediaSelected(media: Media)
    }
}