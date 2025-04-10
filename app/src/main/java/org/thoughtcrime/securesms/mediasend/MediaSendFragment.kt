package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.databinding.MediasendFragmentBinding
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.TextSecurePreferences.Companion.isEnterSendsEnabled
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.components.KeyboardAwareLinearLayout.OnKeyboardHiddenListener
import org.thoughtcrime.securesms.components.KeyboardAwareLinearLayout.OnKeyboardShownListener
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter.RailItemListener
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.util.PushCharacterCalculator
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Allows the user to edit and caption a set of media items before choosing to send them.
 */
@AndroidEntryPoint
class MediaSendFragment : Fragment(), OnGlobalLayoutListener, RailItemListener,
    OnKeyboardShownListener, OnKeyboardHiddenListener {
    private var binding: MediasendFragmentBinding? = null

    private var fragmentPagerAdapter: MediaSendFragmentPagerAdapter? = null
    private var mediaRailAdapter: MediaRailAdapter? = null

    private var visibleHeight = 0
    private var viewModel: MediaSendViewModel? = null

    private val visibleBounds = Rect()

    private val characterCalculator = PushCharacterCalculator()

    private val controller: Controller
        get() = (parentFragment as? Controller) ?: requireActivity() as Controller

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

        binding.mediasendSafeArea.applySafeInsetsPaddings()

        binding.mediasendSendButton.setOnClickListener { v: View? ->
            if (binding.mediasendHud.isKeyboardOpen) {
                binding.mediasendHud.hideSoftkey(binding.mediasendComposeText, null)
            }

            fragmentPagerAdapter?.let { processMedia(it.allMedia, it.savedState) }
        }

        val composeKeyPressedListener = ComposeKeyPressedListener()

        binding.mediasendComposeText.setOnKeyListener(composeKeyPressedListener)
        binding.mediasendComposeText.addTextChangedListener(composeKeyPressedListener)
        binding.mediasendComposeText.setOnClickListener(composeKeyPressedListener)
        binding.mediasendComposeText.setOnFocusChangeListener(composeKeyPressedListener)

        binding.mediasendComposeText.requestFocus()

        fragmentPagerAdapter = MediaSendFragmentPagerAdapter(childFragmentManager)
        binding.mediasendPager.setAdapter(fragmentPagerAdapter)

        val pageChangeListener = FragmentPageChangeListener()
        binding.mediasendPager.addOnPageChangeListener(pageChangeListener)
        binding.mediasendPager.post(Runnable { pageChangeListener.onPageSelected(binding.mediasendPager.currentItem) })

        mediaRailAdapter = MediaRailAdapter(Glide.with(this), this, true)
        binding.mediasendMediaRail.setLayoutManager(
            LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )
        )
        binding.mediasendMediaRail.setAdapter(mediaRailAdapter)

        binding.mediasendHud.getRootView().viewTreeObserver.addOnGlobalLayoutListener(this)
        binding.mediasendHud.addOnKeyboardShownListener(this)
        binding.mediasendHud.addOnKeyboardHiddenListener(this)

        binding.mediasendComposeText.append(viewModel?.body)

        binding.mediasendComposeText.setHint(getString(R.string.message), null)
        binding.mediasendComposeText.setOnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND
            if (isSend) binding.mediasendSendButton.performClick()
            isSend
        }

        binding.mediasendCloseButton.setOnClickListener { requireActivity().onBackPressed() }
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

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
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

    override fun onGlobalLayout() {
        val hud = binding?.mediasendHud ?: return

        hud.rootView.getWindowVisibleDisplayFrame(visibleBounds)

        val currentVisibleHeight = visibleBounds.height()

        if (currentVisibleHeight != visibleHeight) {
            hud.layoutParams.height = currentVisibleHeight
            hud.layout(
                visibleBounds.left,
                visibleBounds.top,
                visibleBounds.right,
                visibleBounds.bottom
            )
            hud.requestLayout()

            visibleHeight = currentVisibleHeight
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

    override fun onKeyboardShown() {
        val binding = binding ?: return

        if (binding.mediasendComposeText.hasFocus()) {
            binding.mediasendMediaRail.visibility = View.VISIBLE
            binding.mediasendComposeContainer.visibility = View.VISIBLE
        } else {
            binding.mediasendMediaRail.visibility = View.GONE
            binding.mediasendComposeContainer.visibility = View.VISIBLE
        }
    }

    override fun onKeyboardHidden() {
        binding?.apply {
            mediasendComposeContainer.visibility = View.VISIBLE
            mediasendMediaRail.visibility = View.VISIBLE
        }
    }

    fun onTouchEventsNeeded(needed: Boolean) {
        binding?.mediasendPager?.isEnabled = !needed
    }

    fun handleBackPress(): Boolean {
        val hud = binding?.mediasendHud ?: return false
        val composeText = binding?.mediasendComposeText ?: return false

        if (hud.isInputOpen) {
            hud.hideCurrentInput(composeText)
            return true
        }
        return false
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

            val playbackControls = fragmentPagerAdapter?.getPlaybackControls(position)
            if (playbackControls != null) {
                val params = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                playbackControls.layoutParams = params
                binding?.mediasendPlaybackControlsContainer?.removeAllViews()
                binding?.mediasendPlaybackControlsContainer?.addView(playbackControls)
            } else {
                binding?.mediasendPlaybackControlsContainer?.removeAllViews()
            }
        }

        viewModel.getBucketId().observe(this) { bucketId: String? ->
            if (bucketId == null) return@observe
            mediaRailAdapter!!.setAddButtonListener { controller.onAddMediaClicked(bucketId) }
        }
    }


    private fun presentCharactersRemaining() {
        val binding = binding ?: return
        val messageBody = binding.mediasendComposeText.textTrimmed
        val characterState = characterCalculator.calculateCharacters(messageBody)

        if (characterState.charactersRemaining <= 15 || characterState.messagesSpent > 1) {
            binding.mediasendCharactersLeft.text = String.format(
                Locale.getDefault(),
                "%d/%d (%d)",
                characterState.charactersRemaining,
                characterState.maxTotalMessageSize,
                characterState.messagesSpent
            )
            binding.mediasendCharactersLeft.visibility = View.VISIBLE
        } else {
            binding.mediasendCharactersLeft.visibility = View.GONE
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
                                                jpegSize to BlobProvider.getInstance()
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
                                    val newUri = BlobProvider.getInstance()
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

            controller.onSendClicked(updatedMedia, binding.mediasendComposeText.textTrimmed)
            delayedShowLoader.cancel()
            binding.loader.isVisible = false
        }
    }

    fun onRequestFullScreen(fullScreen: Boolean) {
        binding?.mediasendCaptionAndRail?.visibility =
            if (fullScreen) View.GONE else View.VISIBLE
    }

    private inner class FragmentPageChangeListener : SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            viewModel!!.onPageChanged(position)
        }
    }

    private inner class ComposeKeyPressedListener : View.OnKeyListener, View.OnClickListener,
        TextWatcher, OnFocusChangeListener {
        var beforeLength: Int = 0

        override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (isEnterSendsEnabled(requireContext())) {
                        binding?.mediasendSendButton?.dispatchKeyEvent(
                            KeyEvent(
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_ENTER
                            )
                        )
                        binding?.mediasendSendButton?.dispatchKeyEvent(
                            KeyEvent(
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_ENTER
                            )
                        )
                        return true
                    }
                }
            }
            return false
        }

        override fun onClick(v: View) {
            val binding = binding ?: return
            binding.mediasendHud.showSoftkey(binding.mediasendComposeText)
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            beforeLength = binding?.mediasendComposeText?.textTrimmed?.length ?: return
        }

        override fun afterTextChanged(s: Editable) {
            presentCharactersRemaining()
            viewModel!!.onBodyChanged(s)
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun onFocusChange(v: View, hasFocus: Boolean) {}
    }

    interface Controller {
        fun onAddMediaClicked(bucketId: String)
        fun onSendClicked(media: List<Media>, body: String)
        fun onNoMediaAvailable()
    }

    companion object {
        private val TAG: String = MediaSendFragment::class.java.simpleName

        private const val KEY_ADDRESS = "address"

        fun newInstance(recipient: Recipient): MediaSendFragment {
            val args = Bundle()
            args.putParcelable(KEY_ADDRESS, recipient.address)

            val fragment = MediaSendFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
