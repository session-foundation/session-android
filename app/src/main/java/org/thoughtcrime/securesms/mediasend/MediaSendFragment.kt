package org.thoughtcrime.securesms.mediasend

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.AsyncTask
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
import android.widget.ImageButton
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.TextSecurePreferences.Companion.isEnterSendsEnabled
import org.session.libsession.utilities.Util.cancelRunnableOnMain
import org.session.libsession.utilities.Util.isEmpty
import org.session.libsession.utilities.Util.runOnMainDelayed
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.ListenableFuture
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.SettableFuture
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.components.ComposeText
import org.thoughtcrime.securesms.components.ControllableViewPager
import org.thoughtcrime.securesms.components.InputAwareLayout
import org.thoughtcrime.securesms.components.KeyboardAwareLinearLayout.OnKeyboardHiddenListener
import org.thoughtcrime.securesms.components.KeyboardAwareLinearLayout.OnKeyboardShownListener
import org.thoughtcrime.securesms.imageeditor.model.EditorModel
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter.RailItemListener
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.util.PushCharacterCalculator
import org.thoughtcrime.securesms.util.Stopwatch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutionException

/**
 * Allows the user to edit and caption a set of media items before choosing to send them.
 */
@AndroidEntryPoint
class MediaSendFragment : Fragment(), OnGlobalLayoutListener, RailItemListener,
    OnKeyboardShownListener, OnKeyboardHiddenListener {
    private var hud: InputAwareLayout? = null
    private var captionAndRail: View? = null
    private var sendButton: ImageButton? = null
    private var composeText: ComposeText? = null
    private var composeContainer: ViewGroup? = null
    private var playbackControlsContainer: ViewGroup? = null
    private var charactersLeft: TextView? = null
    private var closeButton: View? = null
    private var loader: View? = null

    private var fragmentPager: ControllableViewPager? = null
    private var fragmentPagerAdapter: MediaSendFragmentPagerAdapter? = null
    private var mediaRail: RecyclerView? = null
    private var mediaRailAdapter: MediaRailAdapter? = null

    private var visibleHeight = 0
    private var viewModel: MediaSendViewModel? = null
    private var controller: Controller? = null

    private val visibleBounds = Rect()

    private val characterCalculator = PushCharacterCalculator()

    override fun onAttach(context: Context) {
        super.onAttach(context)

        check(requireActivity() is Controller) { "Parent activity must implement controller interface." }

        controller = requireActivity() as Controller
        viewModel = ViewModelProvider(requireActivity()).get(
            MediaSendViewModel::class.java
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.mediasend_fragment, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewModel()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hud = view.findViewById(R.id.mediasend_hud)
        captionAndRail = view.findViewById(R.id.mediasend_caption_and_rail)
        sendButton = view.findViewById(R.id.mediasend_send_button)
        composeText = view.findViewById(R.id.mediasend_compose_text)
        composeContainer = view.findViewById(R.id.mediasend_compose_container)
        fragmentPager = view.findViewById(R.id.mediasend_pager)
        mediaRail = view.findViewById(R.id.mediasend_media_rail)
        playbackControlsContainer = view.findViewById(R.id.mediasend_playback_controls_container)
        charactersLeft = view.findViewById(R.id.mediasend_characters_left)
        closeButton = view.findViewById(R.id.mediasend_close_button)
        loader = view.findViewById(R.id.loader)

        val sendButtonBkg = view.findViewById<View>(R.id.mediasend_send_button_bkg)

        sendButton!!.setOnClickListener(View.OnClickListener { v: View? ->
            if (hud!!.isKeyboardOpen()) {
                hud!!.hideSoftkey(composeText, null)
            }
            processMedia(fragmentPagerAdapter!!.allMedia, fragmentPagerAdapter!!.savedState)
        })

        val composeKeyPressedListener = ComposeKeyPressedListener()

        composeText!!.setOnKeyListener(composeKeyPressedListener)
        composeText!!.addTextChangedListener(composeKeyPressedListener)
        composeText!!.setOnClickListener(composeKeyPressedListener)
        composeText!!.setOnFocusChangeListener(composeKeyPressedListener)

        composeText!!.requestFocus()

        fragmentPagerAdapter = MediaSendFragmentPagerAdapter(childFragmentManager)
        fragmentPager!!.setAdapter(fragmentPagerAdapter)

        val pageChangeListener = FragmentPageChangeListener()
        fragmentPager!!.addOnPageChangeListener(pageChangeListener)
        fragmentPager!!.post(Runnable { pageChangeListener.onPageSelected(fragmentPager!!.currentItem) })

        mediaRailAdapter = MediaRailAdapter(Glide.with(this), this, true)
        mediaRail!!.setLayoutManager(
            LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )
        )
        mediaRail!!.setAdapter(mediaRailAdapter)

        hud!!.getRootView().viewTreeObserver.addOnGlobalLayoutListener(this)
        hud!!.addOnKeyboardShownListener(this)
        hud!!.addOnKeyboardHiddenListener(this)

        composeText!!.append(viewModel!!.body)

        val recipient = Recipient.from(
            requireContext(),
            arguments!!.getParcelable(KEY_ADDRESS)!!, false
        )
        val displayName = Optional.fromNullable(recipient.name)
            .or(
                Optional.fromNullable(recipient.profileName)
                    .or(recipient.address.toString())
            )
        composeText!!.setHint(getString(R.string.message), null)
        composeText!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND
            if (isSend) sendButton!!.performClick()
            isSend
        })

        closeButton!!.setOnClickListener(View.OnClickListener { v: View? -> requireActivity().onBackPressed() })
    }

    override fun onStart() {
        super.onStart()

        fragmentPagerAdapter!!.restoreState(viewModel!!.drawState)
        viewModel!!.onImageEditorStarted()

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
    }

    override fun onStop() {
        super.onStop()
        fragmentPagerAdapter!!.saveAllState()
        viewModel!!.saveDrawState(fragmentPagerAdapter!!.savedState)
    }

    override fun onGlobalLayout() {
        hud!!.rootView.getWindowVisibleDisplayFrame(visibleBounds)

        val currentVisibleHeight = visibleBounds.height()

        if (currentVisibleHeight != visibleHeight) {
            hud!!.layoutParams.height = currentVisibleHeight
            hud!!.layout(
                visibleBounds.left,
                visibleBounds.top,
                visibleBounds.right,
                visibleBounds.bottom
            )
            hud!!.requestLayout()

            visibleHeight = currentVisibleHeight
        }
    }

    override fun onRailItemClicked(distanceFromActive: Int) {
        viewModel!!.onPageChanged(fragmentPager!!.currentItem + distanceFromActive)
    }

    override fun onRailItemDeleteClicked(distanceFromActive: Int) {
        viewModel!!.onMediaItemRemoved(
            requireContext(),
            fragmentPager!!.currentItem + distanceFromActive
        )
    }

    override fun onKeyboardShown() {
        if (composeText!!.hasFocus()) {
            mediaRail!!.visibility = View.VISIBLE
            composeContainer!!.visibility = View.VISIBLE
        } else {
            mediaRail!!.visibility = View.GONE
            composeContainer!!.visibility = View.VISIBLE
        }
    }

    override fun onKeyboardHidden() {
        composeContainer!!.visibility = View.VISIBLE
        mediaRail!!.visibility = View.VISIBLE
    }

    fun onTouchEventsNeeded(needed: Boolean) {
        if (fragmentPager != null) {
            fragmentPager!!.isEnabled = !needed
        }
    }

    fun handleBackPress(): Boolean {
        if (hud!!.isInputOpen) {
            hud!!.hideCurrentInput(composeText)
            return true
        }
        return false
    }

    private fun initViewModel() {
        viewModel!!.getSelectedMedia().observe(
            this
        ) { media: List<Media?>? ->
            if (isEmpty(media)) {
                controller!!.onNoMediaAvailable()
                return@observe
            }
            fragmentPagerAdapter!!.setMedia(media!!)

            mediaRail!!.visibility = View.VISIBLE
            mediaRailAdapter!!.setMedia(media)
        }

        viewModel!!.getPosition().observe(this) { position: Int? ->
            if (position == null || position < 0) return@observe
            fragmentPager!!.setCurrentItem(position, true)
            mediaRailAdapter!!.setActivePosition(position)
            mediaRail!!.smoothScrollToPosition(position)

            val playbackControls = fragmentPagerAdapter!!.getPlaybackControls(position)
            if (playbackControls != null) {
                val params = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                playbackControls.layoutParams = params
                playbackControlsContainer!!.removeAllViews()
                playbackControlsContainer!!.addView(playbackControls)
            } else {
                playbackControlsContainer!!.removeAllViews()
            }
        }

        viewModel!!.getBucketId().observe(this) { bucketId: String? ->
            if (bucketId == null) return@observe
            mediaRailAdapter!!.setAddButtonListener { controller!!.onAddMediaClicked(bucketId) }
        }
    }


    private fun presentCharactersRemaining() {
        val messageBody = composeText!!.textTrimmed
        val characterState = characterCalculator.calculateCharacters(messageBody)

        if (characterState.charactersRemaining <= 15 || characterState.messagesSpent > 1) {
            charactersLeft!!.text = String.format(
                Locale.getDefault(),
                "%d/%d (%d)",
                characterState.charactersRemaining,
                characterState.maxTotalMessageSize,
                characterState.messagesSpent
            )
            charactersLeft!!.visibility = View.VISIBLE
        } else {
            charactersLeft!!.visibility = View.GONE
        }
    }

    @SuppressLint("StaticFieldLeak")
    private fun processMedia(mediaList: List<Media>, savedState: Map<Uri, Any>) {
        val futures: MutableMap<Media, ListenableFuture<Bitmap>> = HashMap()

        for (media in mediaList) {
            val state = savedState[media.uri]

            if (state is ImageEditorFragment.Data) {
                val model = state.readModel()
                if (model != null && model.isChanged) {
                    futures[media] = render(requireContext(), model)
                }
            }
        }

        object : AsyncTask<Void?, Void?, List<Media>>() {
            private var renderTimer: Stopwatch? = null
            private var progressTimer: Runnable? = null

            override fun onPreExecute() {
                renderTimer = Stopwatch("ProcessMedia")
                progressTimer = Runnable {
                    loader!!.visibility = View.VISIBLE
                }
                runOnMainDelayed(progressTimer!!, 250)
            }

            override fun doInBackground(vararg params: Void?): List<Media> {
                val context = requireContext()
                val updatedMedia: MutableList<Media> = ArrayList(mediaList.size)

                for (media in mediaList) {
                    if (futures.containsKey(media)) {
                        try {
                            val bitmap = futures[media]!!.get()
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)

                            val uri = BlobProvider.getInstance()
                                .forData(baos.toByteArray())
                                .withMimeType(MediaTypes.IMAGE_JPEG)
                                .createForSingleSessionOnDisk(
                                    context
                                ) { e: IOException? ->
                                    Log.w(
                                        TAG,
                                        "Failed to write to disk.",
                                        e
                                    )
                                }

                            val updated = Media(
                                uri,
                                media.filename,
                                MediaTypes.IMAGE_JPEG,
                                media.date,
                                bitmap.width,
                                bitmap.height,
                                baos.size().toLong(),
                                media.bucketId,
                                media.caption
                            )

                            updatedMedia.add(updated)
                            renderTimer!!.split("item")
                        } catch (e: InterruptedException) {
                            Log.w(TAG, "Failed to render image. Using base image.")
                            updatedMedia.add(media)
                        } catch (e: ExecutionException) {
                            Log.w(TAG, "Failed to render image. Using base image.")
                            updatedMedia.add(media)
                        } catch (e: IOException) {
                            Log.w(TAG, "Failed to render image. Using base image.")
                            updatedMedia.add(media)
                        }
                    } else {
                        updatedMedia.add(media)
                    }
                }
                return updatedMedia
            }

            override fun onPostExecute(media: List<Media>) {
                controller!!.onSendClicked(media, composeText!!.textTrimmed)
                cancelRunnableOnMain(progressTimer!!)
                loader!!.visibility = View.GONE
                renderTimer!!.stop(TAG)
            }
        }.execute()
    }

    fun onRequestFullScreen(fullScreen: Boolean) {
        captionAndRail!!.visibility =
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
                        sendButton!!.dispatchKeyEvent(
                            KeyEvent(
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_ENTER
                            )
                        )
                        sendButton!!.dispatchKeyEvent(
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
            hud!!.showSoftkey(composeText)
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            beforeLength = composeText!!.textTrimmed.length
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

        private fun render(context: Context, model: EditorModel): ListenableFuture<Bitmap> {
            val future = SettableFuture<Bitmap>()

            AsyncTask.THREAD_POOL_EXECUTOR.execute { future.set(model.render(context)) }

            return future
        }
    }
}
