package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import network.loki.messenger.databinding.MediasendFragmentBinding
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.MediaTypes
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.InputBarDialogs
import org.thoughtcrime.securesms.conversation.v2.input_bar.InputBarDelegate
import org.thoughtcrime.securesms.conversation.v2.mention.MentionViewModel
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter.RailItemListener
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.hideKeyboard
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Allows the user to edit and caption a set of media items before choosing to send them.
 */
@AndroidEntryPoint
class MediaSendFragment : Fragment(), RailItemListener, InputBarDelegate {
    private var binding: MediasendFragmentBinding? = null

    private var fragmentPagerAdapter: MediaSendFragmentPagerAdapter? = null
    private var mediaRailAdapter: MediaRailAdapter? = null

    private var viewModel: MediaSendViewModel? = null

    private val controller: Controller
        get() = (parentFragment as? Controller) ?: requireActivity() as Controller

    private val address: Address
        get() = requireNotNull(BundleCompat.getParcelable(requireArguments(), KEY_ADDRESS, Address::class.java)) {
            "Address is not provided in arguments"
        }

    private val mentionViewModel: MentionViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<MentionViewModel.Factory> {
            it.create(address)
        }
    })

    override fun onAttach(context: Context) {
        super.onAttach(context)

        viewModel = ViewModelProvider(requireActivity()).get(
            MediaSendViewModel::class.java
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return MediasendFragmentBinding.inflate(inflater, container, false).root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewModel()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = MediasendFragmentBinding.bind(view).also {
            this.binding = it
        }

        binding.mediasendSafeArea.applySafeInsetsPaddings(
            applyBottom = false,
            alsoApply = {
                binding.bottomSpacer.updateLayoutParams<LayoutParams> {
                    height = it.bottom
                }
            }
        )

        binding.inputBar.delegate = this
        binding.inputBar.setInputBarEditableFactory(mentionViewModel.editableFactory)

        viewLifecycleOwner.lifecycleScope.launch {
            val pretty = mentionViewModel.reconstructMentions(viewModel?.body?.toString().orEmpty())
            binding.inputBar.setText(pretty, TextView.BufferType.EDITABLE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel?.inputBarState?.collect { state ->
                    binding.inputBar.setState(state)
                }
            }
        }

        fragmentPagerAdapter = MediaSendFragmentPagerAdapter(childFragmentManager)
        binding.mediasendPager.setAdapter(fragmentPagerAdapter)

        val pageChangeListener = FragmentPageChangeListener()
        binding.mediasendPager.addOnPageChangeListener(pageChangeListener)
        binding.mediasendPager.post { pageChangeListener.onPageSelected(binding.mediasendPager.currentItem) }

        mediaRailAdapter = MediaRailAdapter(Glide.with(this), this, true)
        binding.mediasendMediaRail.setLayoutManager(
            LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )
        )
        binding.mediasendMediaRail.setAdapter(mediaRailAdapter)

        binding.mediasendCloseButton.setOnClickListener {
            binding.inputBar.clearFocus()
            binding.root.hideKeyboard()
            requireActivity().finish()
        }

        // set the compose dialog content
        binding.dialogs.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setThemedContent {
                if(viewModel == null) return@setThemedContent
                val dialogsState by viewModel!!.inputBarStateDialogsState.collectAsState()
                InputBarDialogs (
                    inputBarDialogsState = dialogsState,
                    sendCommand = {
                        viewModel?.onInputBarCommand(it)
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        binding = null
    }

    override fun onStart() {
        super.onStart()

        val viewModel = viewModel
        val adapter = fragmentPagerAdapter

        if (viewModel != null && adapter != null) {
            adapter.restoreState(viewModel.drawState)
            viewModel.onImageEditorStarted()
        }
    }

    override fun onStop() {
        super.onStop()

        val viewModel = viewModel
        val adapter = fragmentPagerAdapter

        if (viewModel != null && adapter != null) {
            adapter.saveAllState()
            viewModel.saveDrawState(adapter.savedState)
        }
    }


    override fun onRailItemClicked(distanceFromActive: Int) {
        val currentItem = binding?.mediasendPager?.currentItem ?: return
        viewModel?.onPageChanged(currentItem + distanceFromActive)
    }

    override fun onRailItemDeleteClicked(distanceFromActive: Int) {
        val currentItem = binding?.mediasendPager?.currentItem ?: return

        viewModel?.onMediaItemRemoved(
            requireContext(),
            currentItem + distanceFromActive
        )
    }

    fun onTouchEventsNeeded(needed: Boolean) {
        binding?.mediasendPager?.isEnabled = !needed
    }

    private fun initViewModel() {
        val viewModel = requireNotNull(viewModel) {
            "ViewModel is not initialized"
        }

        viewModel.getSelectedMedia().observe(
            this
        ) { media: List<Media?>? ->
            if (media.isNullOrEmpty()) {
                controller.onNoMediaAvailable()
                return@observe
            }

            fragmentPagerAdapter?.setMedia(media)

            binding?.mediasendMediaRail?.visibility = View.VISIBLE
            mediaRailAdapter?.setMedia(media)
        }

        viewModel.getPosition().observe(this) { position: Int? ->
            if (position == null || position < 0) return@observe
            binding?.mediasendPager?.setCurrentItem(position, true)
            mediaRailAdapter?.setActivePosition(position)
            binding?.mediasendMediaRail?.smoothScrollToPosition(position)
        }

        viewModel.getBucketId().observe(this) { bucketId: String? ->
            if (bucketId == null) return@observe
            mediaRailAdapter!!.setAddButtonListener {
                // save existing text in VM
                viewModel.onBodyChanged(mentionViewModel.deconstructMessageMentions())

                controller.onAddMediaClicked(bucketId)
            }
        }
    }

    private fun processMedia(mediaList: List<Media>, savedState: Map<Uri, Any>) {
        val binding = binding ?: return // If the view is destroyed, this process should not continue

        val context = requireContext().applicationContext

        lifecycleScope.launch {
            val delayedShowLoader = launch {
                delay(250)
                binding.loader.isVisible = true
            }

            val updatedMedia = supervisorScope {
                // For each media, render the image in the background if necessary
                val renderingTasks = mediaList
                    .asSequence()
                    .map { media ->
                        media to (savedState[media.uri] as? ImageEditorFragment.Data)
                            ?.readModel()
                            ?.takeIf { it.isChanged }
                    }
                    .associate { (media, model) ->
                        media.uri to async {
                            runCatching {
                                if (model != null) {
                                    // While we render the bitmap in the background, make sure
                                    // we limit the number of parallel tasks to avoid overwhelming the memory,
                                    // as bitmaps are memory intensive.
                                    withContext(Dispatchers.Default.limitedParallelism(2)) {
                                        val bitmap = model.render(context)
                                        try {
                                            // Compress the bitmap to JPEG
                                            val jpegOut = requireNotNull(
                                                File.createTempFile(
                                                    "media_preview",
                                                    ".jpg",
                                                    context.cacheDir
                                                )
                                            ) {
                                                "Unable to create temporary file"
                                            }

                                            val (jpegSize, uri) = try {
                                                FileOutputStream(jpegOut).use { out ->
                                                    bitmap.compress(
                                                        Bitmap.CompressFormat.JPEG,
                                                        80,
                                                        out
                                                    )
                                                }

                                                // Once we have the JPEG file, save it as our blob
                                                val jpegSize = jpegOut.length()
                                                jpegSize to BlobUtils.getInstance()
                                                    .forData(FileInputStream(jpegOut), jpegSize)
                                                    .withMimeType(MediaTypes.IMAGE_JPEG)
                                                    .withFileName(media.filename)
                                                    .createForSingleSessionOnDisk(context, null)
                                                    .await()
                                            } finally {
                                                // Clean up the temporary file
                                                jpegOut.delete()
                                            }

                                            media.copy(
                                                uri = uri,
                                                mimeType = MediaTypes.IMAGE_JPEG,
                                                width = bitmap.width,
                                                height = bitmap.height,
                                                size = jpegSize,
                                            )
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                } else {
                                    // No changes to the original media, copy and return as is
                                    val newUri = BlobUtils.getInstance()
                                        .forData(requireNotNull(context.contentResolver.openInputStream(media.uri)) {
                                            "Invalid URI"
                                        }, media.size)
                                        .withMimeType(media.mimeType)
                                        .withFileName(media.filename)
                                        .createForSingleSessionOnDisk(context, null)
                                        .await()

                                    media.copy(uri = newUri)
                                }
                            }
                        }
                    }

                // For each media, if there's a rendered version, use that or keep the original
                mediaList.map { media ->
                    renderingTasks[media.uri]?.await()?.let { rendered ->
                        if (rendered.isFailure) {
                            Log.w(TAG, "Error rendering image", rendered.exceptionOrNull())
                            media
                        } else {
                            rendered.getOrThrow()
                        }
                    } ?: media
                }
            }

            controller.onSendClicked(updatedMedia, mentionViewModel.normalizeMessageBody())
            delayedShowLoader.cancel()
            binding.loader.isVisible = false
            binding.inputBar.clearFocus()
            binding.root.hideKeyboard()
        }
    }

    fun onRequestFullScreen(fullScreen: Boolean) {
        binding?.mediasendCaptionAndRail?.visibility =
            if (fullScreen) View.GONE else View.VISIBLE
    }

    override fun inputBarEditTextContentChanged(newContent: CharSequence) {
        // use the normalised version of the text's body to get the characters amount with the
        // mentions as their account id
        viewModel?.onTextChanged(mentionViewModel.deconstructMessageMentions())
    }

    override fun sendMessage() {
        // validate message length before sending
        if(viewModel == null || viewModel?.validateMessageLength() == false) return

        fragmentPagerAdapter?.let { processMedia(it.allMedia, it.savedState) }
    }

    override fun onCharLimitTapped() {
        viewModel?.onCharLimitTapped()
    }

    // Unused callbacks
    override fun commitInputContent(contentUri: Uri) {}
    override fun toggleAttachmentOptions() {}
    override fun showVoiceMessageUI() {}
    override fun startRecordingVoiceMessage() {}
    override fun onMicrophoneButtonMove(event: MotionEvent) {}
    override fun onMicrophoneButtonCancel(event: MotionEvent) {}
    override fun onMicrophoneButtonUp(event: MotionEvent) {}

    private inner class FragmentPageChangeListener : SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            viewModel!!.onPageChanged(position)
        }
    }

    interface Controller {
        fun onAddMediaClicked(bucketId: String)
        fun onSendClicked(media: List<Media>, body: String)
        fun onNoMediaAvailable()
    }

    companion object {
        private val TAG: String = MediaSendFragment::class.java.simpleName

        private const val KEY_ADDRESS = "address"

        fun newInstance(address: Address): MediaSendFragment {
            val args = Bundle(1)
            args.putParcelable(KEY_ADDRESS, address)

            val fragment = MediaSendFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
