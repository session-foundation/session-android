/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Observer
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.MediaPreviewActivityBinding
import network.loki.messenger.databinding.MediaViewPageBinding
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.DataExtractionNotification.Kind.MediaSaved
import org.session.libsession.messaging.sending_receiving.MessageSender.send
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.components.MediaView
import org.thoughtcrime.securesms.components.dialogs.DeleteMediaPreviewDialog
import org.thoughtcrime.securesms.database.MediaDatabase.MediaRecord
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.loaders.PagingMediaLoader
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.media.MediaOverviewActivity
import org.thoughtcrime.securesms.mediapreview.MediaPreviewViewModel
import org.thoughtcrime.securesms.mediapreview.MediaPreviewViewModel.PreviewData
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter.RailItemListener
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.util.AttachmentUtil
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.FilenameUtils.getFilenameFromUri
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.SaveAttachmentTask.Companion.showOneTimeWarningDialogOrSave
import java.io.IOException
import java.util.Locale
import java.util.WeakHashMap
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Activity for displaying media attachments in-app
 */
@AndroidEntryPoint
class MediaPreviewActivity : ScreenLockActionBarActivity(),
    LoaderManager.LoaderCallbacks<Pair<Cursor, Int>?>,
    RailItemListener, MediaView.FullscreenToggleListener {
    private lateinit var binding: MediaPreviewActivityBinding
    private var initialMediaUri: Uri? = null
    private var initialMediaType: String? = null
    private var initialMediaSize: Long = 0
    private var initialCaption: String? = null
    private var conversationRecipient: Address? = null
    private var leftIsRecent = false
    private val viewModel: MediaPreviewViewModel by viewModels()
    private var viewPagerListener: ViewPagerListener? = null
    
    @Inject
    lateinit var deprecationManager: LegacyGroupDeprecationManager

    private var isFullscreen = false

    @Inject
    lateinit var dateUtils: DateUtils

    @Inject
    lateinit var recipientRepository: RecipientRepository

    override val applyDefaultWindowInsets: Boolean
        get() = false

    private var adapter: CursorPagerAdapter? = null
    private var albumRailAdapter: MediaRailAdapter? = null

    private var windowInsetBottom = 0
    private var railHeight = 0

    override fun onCreate(bundle: Bundle?, ready: Boolean) {
        binding = MediaPreviewActivityBinding.inflate(
            layoutInflater
        )

        setContentView(binding.root)

        initializeViews()
        initializeResources()
        initializeObservers()
        initializeMedia()

        // make the toolbar translucent so that the video can be seen below in landscape - 70% of regular toolbar color
        supportActionBar?.setBackgroundDrawable(
            ColorUtils.setAlphaComponent(
                getColorFromAttr(
                    android.R.attr.colorPrimary
                ), (0.7f * 255).toInt()
            ).toDrawable())

        // handle edge to edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            windowInsetBottom = insets.bottom

            binding.toolbar.updatePadding(top = insets.top)
            binding.mediaPreviewAlbumRailContainer.updatePadding(bottom = max(insets.bottom, binding.mediaPreviewAlbumRailContainer.paddingBottom))

            updateControlsPosition()

            // on older android version this acts as a safety when the system intercepts the first tap and only
            // shows the system bars but ignores our code to show the toolbar and rail back
            val systemBarsVisible: Boolean = windowInsets.isVisible(WindowInsetsCompat.Type.systemBars())
            if (systemBarsVisible && isFullscreen) {
                exitFullscreen()
            }

            windowInsets.inset(insets)
        }


        // Set up system UI visibility listener
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // Check if system bars became visible
            val systemBarsVisible = (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
            if (systemBarsVisible && isFullscreen) {
                // System bars appeared - exit fullscreen and show our UI
                exitFullscreen()
            }
        }
    }

    /**
     * Updates the media controls' position based on the rail's position
     */
    private fun updateControlsPosition() {
        // the ypos of the controls is either the window bottom inset, or the rail height if there is a rail
        // since the rail height takes the window inset into account with its padding
        val totalBottomPadding = max(
            windowInsetBottom,
            railHeight + resources.getDimensionPixelSize(R.dimen.medium_spacing)
        )

        adapter?.setControlsYPosition(totalBottomPadding)
    }

    override fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    override fun setFullscreen(displayFullscreen: Boolean) {
        if (displayFullscreen) enterFullscreen() else exitFullscreen()
    }

    private fun enterFullscreen() {
        supportActionBar?.hide()
        hideAlbumRail()
        isFullscreen = true
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen() {
        supportActionBar?.show()
        showAlbumRail()
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        isFullscreen = false
    }

    private fun hideAlbumRail() {
        val rail = binding.mediaPreviewAlbumRailContainer
        rail.animate().cancel()
        rail.animate()
            .translationY(rail.height.toFloat())
            .alpha(0f)
            .setDuration(200)
            .withEndAction { rail.visibility = View.GONE }
            .start()
    }

    private fun showAlbumRail() {
        // never show the rail in landscape
        if(isLandscape()) return

        val rail = binding.mediaPreviewAlbumRailContainer
        rail.animate().cancel()
        rail.visibility = View.VISIBLE
        rail.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }

    override fun onRailItemClicked(distanceFromActive: Int) {
        binding.mediaPager.currentItem = binding.mediaPager.currentItem + distanceFromActive
    }

    override fun onRailItemDeleteClicked(distanceFromActive: Int) {
        throw UnsupportedOperationException("Callback unsupported.")
    }

    private fun updateActionBar() {
        val mediaItem = currentMediaItem

        if (mediaItem != null) {
            val relativeTimeSpan: CharSequence = if (mediaItem.date > 0) {
                dateUtils.getDisplayFormattedTimeSpanString(mediaItem.date)
            } else {
                getString(R.string.draft)
            }

            if (mediaItem.outgoing) supportActionBar?.title = getString(R.string.you)
            else if (mediaItem.recipient != null) supportActionBar?.title =
                mediaItem.recipient.displayName
            else supportActionBar?.title = ""

            supportActionBar?.subtitle = relativeTimeSpan
        }
    }

    public override fun onPause() {
        super.onPause()

        adapter?.pause(binding.mediaPager.currentItem)
    }

    override fun onDestroy() {
        adapter?.cleanUp()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initializeResources()
    }

    fun onMediaPaused(videoUri: Uri, position: Long){
        viewModel.savePlaybackPosition(videoUri, position)
    }

    fun getLastPlaybackPosition(videoUri: Uri): Long {
        return viewModel.getSavedPlaybackPosition(videoUri)
    }

    private fun initializeViews() {
        binding.mediaPager.offscreenPageLimit = 1

        albumRailAdapter = MediaRailAdapter(Glide.with(this), this, false)

        binding.mediaPreviewAlbumRail.layoutManager =
            LinearLayoutManager(
                this,
                LinearLayoutManager.HORIZONTAL,
                false
            )
        binding.mediaPreviewAlbumRail.adapter = albumRailAdapter

        setSupportActionBar(findViewById(R.id.toolbar))

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
    }

    private fun initializeResources() {
        conversationRecipient = IntentCompat.getParcelableExtra(intent,
            ADDRESS_EXTRA,
            Address::class.java
        )

        initialMediaUri = intent.data
        initialMediaType = intent.type
        initialMediaSize = intent.getLongExtra(SIZE_EXTRA, 0)
        initialCaption = intent.getStringExtra(CAPTION_EXTRA)
        leftIsRecent = intent.getBooleanExtra(LEFT_IS_RECENT_EXTRA, false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // always hide the rail in landscape
        if (isLandscape()) {
            hideAlbumRail()
        } else {
            if (!isFullscreen) {
                showAlbumRail()
            }
        }

        // Re-apply fullscreen if we were already in it
        if (isFullscreen) {
            enterFullscreen()
        } else {
            exitFullscreen()
        }
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun initializeObservers() {
        viewModel.previewData.observe(
            this,
            Observer<PreviewData> { previewData: PreviewData? ->
                if (previewData == null || binding == null || binding.mediaPager.adapter == null) {
                    return@Observer
                }
                
                binding.mediaPreviewAlbumRailContainer.visibility =
                    if (previewData.albumThumbnails.isEmpty()) View.GONE else View.VISIBLE
                albumRailAdapter?.setMedia(previewData.albumThumbnails, previewData.activePosition)

                // recalculate controls position if we have rail data
                if(previewData.albumThumbnails.isNotEmpty()) {
                    binding.mediaPreviewAlbumRailContainer.viewTreeObserver.addOnGlobalLayoutListener(
                        object : ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                binding.mediaPreviewAlbumRailContainer.viewTreeObserver.removeOnGlobalLayoutListener(
                                    this
                                )
                                railHeight = binding.mediaPreviewAlbumRailContainer.height
                                updateControlsPosition()
                            }
                        }
                    )
                }

                binding.mediaPreviewAlbumRail.smoothScrollToPosition(previewData.activePosition)
            })
    }

    private fun initializeMedia() {
        if (!isContentTypeSupported(initialMediaType)) {
            Log.w(TAG, "Unsupported media type sent to MediaPreviewActivity, finishing.")
            Toast.makeText(
                applicationContext,
                R.string.attachmentsErrorNotSupported,
                Toast.LENGTH_LONG
            ).show()
            finish()
        }

        Log.i(
            TAG,
            "Loading Part URI: $initialMediaUri"
        )

        if (conversationRecipient != null) {
            LoaderManager.getInstance(this).restartLoader(0, null, this)
        } else {
            finish()
        }
    }

    private fun showOverview() {
        conversationRecipient?.let { startActivity(MediaOverviewActivity.createIntent(this, it)) }
    }

    private fun forward() {
        val mediaItem = currentMediaItem

        if (mediaItem != null) {
            val composeIntent = Intent(
                this,
                ShareActivity::class.java
            )
            composeIntent.putExtra(Intent.EXTRA_STREAM, mediaItem.uri)
            composeIntent.setType(mediaItem.mimeType)
            startActivity(composeIntent)
        }
    }

    @SuppressLint("InlinedApi")
    private fun saveToDisk() {
        val mediaItem = currentMediaItem
        if (mediaItem == null) {
            Log.w(TAG, "Cannot save a null MediaItem to disk - bailing.")
            return
        }

        // If we have an attachment then we can take the filename from it, otherwise we have to take the
        // more expensive route of looking up or synthesizing a filename from the MediaItem's Uri.
        var mediaFilename = ""
        if (mediaItem.attachment != null) {
            mediaFilename = mediaItem.attachment.filename
        }

        if (mediaFilename == null || mediaFilename.isEmpty()) {
            mediaFilename =
                getFilenameFromUri(this@MediaPreviewActivity, mediaItem.uri, mediaItem.mimeType)
        }

        val outputFilename = mediaFilename // We need a final value for the saveTask, below
        Log.i(
            TAG,
            "About to save media as: $outputFilename"
        )

        showOneTimeWarningDialogOrSave(this, 1) {
            Permissions.with(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .maxSdkVersion(Build.VERSION_CODES.P) // Note: P is API 28
                .withPermanentDenialDialog(permanentlyDeniedStorageText)
                .onAnyDenied {
                    Toast.makeText(
                        this,
                        permanentlyDeniedStorageText, Toast.LENGTH_LONG
                    ).show()
                }
                .onAllGranted {
                    val saveTask = SaveAttachmentTask(this@MediaPreviewActivity)
                    val saveDate = if (mediaItem.date > 0) mediaItem.date else nowWithOffset
                    saveTask.executeOnExecutor(
                        AsyncTask.THREAD_POOL_EXECUTOR,
                        SaveAttachmentTask.Attachment(
                            mediaItem.uri,
                            mediaItem.mimeType,
                            saveDate,
                            outputFilename
                        )
                    )
                    if (!mediaItem.outgoing) {
                        sendMediaSavedNotificationIfNeeded()
                    }
                }
                .execute()
            Unit
        }
    }

    private val permanentlyDeniedStorageText: String
        get() = Phrase.from(
            applicationContext,
            R.string.permissionsStorageDeniedLegacy
        )
            .put(APP_NAME_KEY, getString(R.string.app_name))
            .format().toString()

    private fun sendMediaSavedNotificationIfNeeded() {
        if (conversationRecipient == null || conversationRecipient?.isGroupOrCommunity == true) return
        val message = DataExtractionNotification(
            MediaSaved(
                nowWithOffset
            )
        )
        send(message, conversationRecipient!!)
    }

    @SuppressLint("StaticFieldLeak")
    private fun deleteMedia() {
        val mediaItem = currentMediaItem
        if (mediaItem?.attachment == null) {
            return
        }

        DeleteMediaPreviewDialog.show(this){
            AttachmentUtil.deleteAttachment(applicationContext, mediaItem.attachment)
            finish()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        menu.clear()
        val inflater = this.menuInflater
        inflater.inflate(R.menu.media_preview, menu)

        val isDeprecatedLegacyGroup = conversationRecipient != null &&
                conversationRecipient?.isLegacyGroup == true &&
                deprecationManager.deprecationState.value == LegacyGroupDeprecationManager.DeprecationState.DEPRECATED

        if (!isMediaInDb || isDeprecatedLegacyGroup) {
            menu.findItem(R.id.media_preview__overview).setVisible(false)
            menu.findItem(R.id.delete).setVisible(false)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        super.onOptionsItemSelected(item)

        when (item.itemId) {
            R.id.media_preview__overview -> {
                showOverview()
                return true
            }

            R.id.media_preview__forward -> {
                forward()
                return true
            }

            R.id.save -> {
                saveToDisk()
                return true
            }

            R.id.delete -> {
                deleteMedia()
                return true
            }

            android.R.id.home -> {
                finish()
                return true
            }
        }

        return false
    }

    private val isMediaInDb: Boolean
        get() = conversationRecipient != null

    private val currentMediaItem: MediaItem?
        get() {
            if (adapter == null) return null

            try {
                return adapter!!.getMediaItemFor(binding.mediaPager.currentItem)
            } catch (e: Exception) {
                Log.w(TAG, "Error getting current media item", e)
                return null
            }
        }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Pair<Cursor, Int>?> {
        return PagingMediaLoader(
            this,
            conversationRecipient!!, initialMediaUri!!, leftIsRecent
        )
    }

    override fun onLoadFinished(loader: Loader<Pair<Cursor, Int>?>, data: Pair<Cursor, Int>?) {
        if (data == null) return

        viewPagerListener?.let{ binding.mediaPager.unregisterOnPageChangeCallback(it) }

        adapter = CursorPagerAdapter(
            this, Glide.with(this),
            window, data.first, data.second, leftIsRecent
        )
        binding.mediaPager.adapter = adapter

        updateControlsPosition()

        viewModel.setCursor(this, data.first, leftIsRecent)

        val item = max(min(data.second, adapter!!.itemCount - 1), 0)

        viewPagerListener = ViewPagerListener()
        binding.mediaPager.registerOnPageChangeCallback(viewPagerListener!!)

        try {
            binding.mediaPager.setCurrentItem(item, false)
        } catch (e: CursorIndexOutOfBoundsException) {
            throw RuntimeException(
                "data.second = " + data.second + " leftIsRecent = " + leftIsRecent, e
            )
        }

        if (item == 0) {
            viewPagerListener?.onPageSelected(0)
        }
    }

    override fun onLoaderReset(loader: Loader<Pair<Cursor, Int>?>) { /* Do nothing */
    }

    private inner class ViewPagerListener : ViewPager2.OnPageChangeCallback() {
        private var currentPage = -1

        override fun onPageSelected(position: Int) {
            if (currentPage != -1 && currentPage != position) onPageUnselected(currentPage)
            currentPage = position

            if (adapter == null) return

            try {
                val item = adapter!!.getMediaItemFor(position)
                viewModel.setActiveAlbumRailItem(this@MediaPreviewActivity, position)
                updateActionBar()
            } catch (e: Exception){
                finish()
            }
        }


        fun onPageUnselected(position: Int) {
            if (adapter == null) return

            try {
                val item = adapter!!.getMediaItemFor(position)
            } catch (e: CursorIndexOutOfBoundsException) {
                throw RuntimeException("position = $position leftIsRecent = $leftIsRecent", e)
            } catch (e: Exception){
                finish()
            }

            adapter!!.pause(position)
        }

        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        ) {
            /* Do nothing */
        }

        override fun onPageScrollStateChanged(state: Int) { /* Do nothing */
        }
    }

    private inner class CursorPagerAdapter(
        context: Context,
        private val glideRequests: RequestManager,
        private val window: Window, private val cursor: Cursor, private var autoPlayPosition: Int,
        private val leftIsRecent: Boolean
    ) : MediaItemAdapter() {
        private val mediaViews = WeakHashMap<Int, MediaView>()

        private val context: Context = context

        private var controlsYPosition: Int = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return object : RecyclerView.ViewHolder(
                MediaViewPageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ).root
            ) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val binding = MediaViewPageBinding.bind(holder.itemView)

            val autoplay = position == autoPlayPosition
            val cursorPosition = getCursorPosition(position)

            autoPlayPosition = -1

            cursor.moveToPosition(cursorPosition)

            val mediaRecord = MediaRecord.from(context, cursor)

            // Set fullscreen toggle listener
            (context as? MediaPreviewActivity)?.let { binding.mediaView.setFullscreenToggleListener(it) }

            try {
                if (mediaRecord.attachment.dataUri == null) throw AssertionError()
                binding.mediaView.set(glideRequests, window, mediaRecord.attachment.dataUri!!, mediaRecord.attachment.contentType, mediaRecord.attachment.size, autoplay)
                binding.mediaView.setControlsYPosition(controlsYPosition)

                // try to resume where we were if we have a saved playback position
                val playbackPosition = (context as? MediaPreviewActivity)?.getLastPlaybackPosition(mediaRecord.attachment.dataUri!!)
                if(playbackPosition != 0L) binding.mediaView.seek(playbackPosition)
            } catch (e: IOException) {
                Log.w(TAG, e)
            }

            mediaViews[position] = binding.mediaView
        }

        override fun getItemCount(): Int {
            return cursor.count
        }

        override fun getMediaItemFor(position: Int): MediaItem {
            cursor.moveToPosition(getCursorPosition(position))
            val mediaRecord = MediaRecord.from(context, cursor)
            val address = mediaRecord.address

            if (mediaRecord.attachment.dataUri == null) throw AssertionError()

            return MediaItem(
                address?.let(recipientRepository::getRecipientSync),
                mediaRecord.attachment,
                mediaRecord.attachment.dataUri!!,
                mediaRecord.contentType,
                mediaRecord.date,
                mediaRecord.isOutgoing
            )
        }

        override fun pause(position: Int) {
            val mediaView = mediaViews[position]
            val playbackPosition = mediaView?.pause() ?: 0L
            // save the last playback position on pause
            (context as? MediaPreviewActivity)?.onMediaPaused(getMediaItemFor(position).uri, playbackPosition)
        }

        override fun cleanUp() {
            mediaViews.forEach{
                it.value.cleanup()
            }
        }

        fun getCursorPosition(position: Int): Int {
            val unclamped = if (leftIsRecent) position else cursor.count - 1 - position
            return max(min(unclamped, cursor.count - 1), 0)
        }

        override fun setControlsYPosition(position: Int){
            controlsYPosition = position

            // Update all existing MediaViews immediately
            mediaViews.values.forEach { mediaView ->
                mediaView.setControlsYPosition(position)
            }
        }
    }

    class MediaItem(
        val recipient: Recipient?,
        val attachment: DatabaseAttachment?,
        val uri: Uri,
        val mimeType: String,
        val date: Long,
        val outgoing: Boolean
    )

    internal abstract class MediaItemAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder?>() {
        abstract fun getMediaItemFor(position: Int): MediaItem
        abstract fun pause(position: Int)
        abstract fun cleanUp()
        abstract fun setControlsYPosition(position: Int)
    }

    companion object {
        private val TAG: String = MediaPreviewActivity::class.java.simpleName

        const val ADDRESS_EXTRA: String = "address"
        const val DATE_EXTRA: String = "date"
        const val SIZE_EXTRA: String = "size"
        const val CAPTION_EXTRA: String = "caption"
        const val OUTGOING_EXTRA: String = "outgoing"
        const val LEFT_IS_RECENT_EXTRA: String = "left_is_recent"

        fun getPreviewIntent(context: Context?, args: MediaPreviewArgs): Intent? {
            return getPreviewIntent(
                context, args.slide,
                args.mmsRecord,
                args.thread
            )
        }

        fun getPreviewIntent(
            context: Context?,
            slide: Slide,
            mms: MmsMessageRecord,
            threadRecipient: Address
        ): Intent? {
            var previewIntent: Intent? = null
            if (isContentTypeSupported(slide.contentType) && slide.uri != null) {
                previewIntent = Intent(context, MediaPreviewActivity::class.java)
                previewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .setDataAndType(slide.uri, slide.contentType)
                    .putExtra(ADDRESS_EXTRA, threadRecipient)
                    .putExtra(OUTGOING_EXTRA, mms.isOutgoing)
                    .putExtra(DATE_EXTRA, mms.timestamp)
                    .putExtra(SIZE_EXTRA, slide.asAttachment().size)
                    .putExtra(CAPTION_EXTRA, slide.caption.orNull())
                    .putExtra(LEFT_IS_RECENT_EXTRA, false)
            }
            return previewIntent
        }


        fun isContentTypeSupported(contentType: String?): Boolean {
            return contentType != null && (contentType.startsWith("image/") || contentType.startsWith(
                "video/"
            ))
        }
    }
}