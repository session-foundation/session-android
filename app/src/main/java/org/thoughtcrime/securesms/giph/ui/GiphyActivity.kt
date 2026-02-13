package org.thoughtcrime.securesms.giph.ui;

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.databinding.GiphyActivityBinding
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.ViewUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.giph.ui.compose.GiphyTabsCompose
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.ui.setThemedContent

class GiphyActivity :
    ScreenLockActionBarActivity(),
    GiphyActivityToolbar.OnLayoutChangedListener,
    GiphyActivityToolbar.OnFilterChangedListener,
    GiphyAdapter.OnItemClickListener {

    companion object {
        private val TAG = GiphyActivity::class.java.simpleName

        const val EXTRA_IS_MMS = "extra_is_mms"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
    }

    private lateinit var binding: GiphyActivityBinding

    private lateinit var gifFragment: GiphyGifFragment
    private lateinit var stickerFragment: GiphyStickerFragment
    private var forMms: Boolean = false

    private var finishingImage: GiphyAdapter.GiphyViewHolder? = null

    private val titles = listOf(
        R.string.gif,
        R.string.stickers
    )

    override fun onCreate(bundle: Bundle?, ready: Boolean) {
        binding = GiphyActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeToolbar()
        initializeResources()

        val pager = binding.giphyPager

        binding.composeTabs.apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setThemedContent {
                GiphyTabsCompose(pager, titles)
            }
        }
    }

    private fun initializeToolbar() {
        val toolbar: GiphyActivityToolbar = ViewUtil.findById(this, R.id.giphy_toolbar)
        toolbar.setOnFilterChangedListener(this)
        toolbar.setOnLayoutChangedListener(this)
        toolbar.setPersistence(GiphyActivityToolbarTextSecurePreferencesPersistence.fromContext(this))

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeButtonEnabled(true)
        }
    }

    private fun initializeResources() {
        gifFragment = GiphyGifFragment()
        stickerFragment = GiphyStickerFragment()
        forMms = intent.getBooleanExtra(EXTRA_IS_MMS, false)

        binding.giphyPager.adapter = GiphyFragmentPagerAdapter(this)
    }

    // Toolbar -> fragments
    override fun onFilterChanged(filter: String) {
        gifFragment.setNewSearchString(filter)
        stickerFragment.setNewSearchString(filter)
    }

    override fun onLayoutChanged(gridLayout: Boolean) {
        gifFragment.setLayoutManager(gridLayout)
        stickerFragment.setLayoutManager(gridLayout)
    }

    override fun onClick(viewHolder: GiphyAdapter.GiphyViewHolder) {
        finishingImage?.gifProgress?.visibility = View.GONE
        finishingImage = viewHolder
        finishingImage?.gifProgress?.visibility = View.VISIBLE

        lifecycleScope.launch {
            val uri: Uri? = withContext(Dispatchers.IO) {
                try {
                    val data = viewHolder.getData(forMms)
                    BlobUtils.getInstance()
                        .forData(data)
                        .withMimeType(MediaTypes.IMAGE_GIF)
                        .createForSingleSessionOnDisk(
                            this@GiphyActivity
                        ) { e -> Log.w(TAG, "Failed to write to disk.", e) }
                        .get()
                } catch (t: Throwable) {
                    Log.w(TAG, t)
                    null
                }
            }

            if (isFinishing || isDestroyed) return@launch
            finishingImage?.gifProgress?.visibility = View.GONE

            if (uri == null) {
                Toast.makeText(this@GiphyActivity, R.string.errorUnknown, Toast.LENGTH_LONG).show()
                return@launch
            }

            // Only finish if the same cell is still the "finishing" one
            if (viewHolder === finishingImage) {
                val result = Intent().apply {
                    data = uri
                    putExtra(EXTRA_WIDTH, viewHolder.image.getGifWidth())
                    putExtra(EXTRA_HEIGHT, viewHolder.image.getGifHeight())
                }
                setResult(RESULT_OK, result)
                finish()
            } else {
                Log.w(TAG, "Resolved Uri is no longer the selected element...")
            }
        }
    }

    private inner class GiphyFragmentPagerAdapter(activity: FragmentActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return if (position == 0) gifFragment else stickerFragment
        }
    }
}
