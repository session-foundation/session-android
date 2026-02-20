package org.thoughtcrime.securesms.giph.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.ViewUtil
import org.thoughtcrime.securesms.giph.model.GiphyImage
import org.thoughtcrime.securesms.giph.net.GiphyLoader
import org.thoughtcrime.securesms.giph.ui.compose.GiphyOverlayState
import org.thoughtcrime.securesms.giph.ui.compose.bindGiphyOverlay
import org.thoughtcrime.securesms.giph.util.InfiniteScrollListener
import java.util.LinkedList
import java.util.List

/**
 * Base fragment for both GIF and Sticker tabs.
 * Subclasses (Gif/Sticker) only implement onCreateLoader(...).
 */
abstract class GiphyFragment :
    Fragment(),
    LoaderManager.LoaderCallbacks<List<GiphyImage>> {

    private lateinit var giphyAdapter: GiphyAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var overlayView: ComposeView
    private val overlayState: MutableStateFlow<GiphyOverlayState> = MutableStateFlow(
        GiphyOverlayState.Hidden
    )

    // Set by toolbar filter via Activity
    var searchString: String? = null

    // If setLayoutManager is called before views are ready
    private var pendingGridLayout: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?, // nullable per Fragment API
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.giphy_fragment, container, false) as ViewGroup

        // Todo: Make compose
        recyclerView = root.findViewById(R.id.giphy_list)

        overlayView = ViewUtil.findById(root, R.id.giphy_state_overlay)
        bindGiphyOverlay(overlayView, overlayState)

        applySearchStringToUI()

        pendingGridLayout?.let {
            setLayoutManager(it)
            pendingGridLayout = null
        } ?: run {
            setLayoutManager(TextSecurePreferences.isGifSearchInGridLayout(requireContext()))
        }

        overlayState.value = GiphyOverlayState.Loading

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        giphyAdapter = GiphyAdapter(requireActivity(), Glide.with(this), LinkedList())
        giphyAdapter.setListener { viewHolder ->
            (activity as? GiphyAdapter.OnItemClickListener)?.onClick(
                viewHolder
            )
        }

        recyclerView.itemAnimator = DefaultItemAnimator()
        recyclerView.adapter = giphyAdapter
        recyclerView.addOnScrollListener(GiphyScrollListener())

        // Initialize the loader (id = 0)
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    // Loader callbacks (subclasses provide the loader)
    abstract override fun onCreateLoader(
        id: Int,
        args: Bundle?
    ): Loader<List<GiphyImage>>

    override fun onLoadFinished(
        loader: Loader<List<GiphyImage>>,
        data: List<GiphyImage>
    ) {
        overlayState.value = if (data.isEmpty()) GiphyOverlayState.Empty() else GiphyOverlayState.Hidden
        giphyAdapter.setImages(data.toMutableList())
    }

    override fun onLoaderReset(loader: Loader<List<GiphyImage>>) {
        overlayState.value = GiphyOverlayState.Hidden
        giphyAdapter.setImages(mutableListOf())
    }

    fun setLayoutManager(gridLayout: Boolean) {
        if (this::recyclerView.isInitialized) {
            recyclerView.layoutManager = createLayoutManager(gridLayout)
        } else {
            pendingGridLayout = gridLayout
        }
    }

    fun setNewSearchString(newSearch: String?) {
        searchString = newSearch
        if (isAdded) applySearchStringToUI()
    }

    private fun createLayoutManager(gridLayout: Boolean): RecyclerView.LayoutManager {
        return if (gridLayout) {
            StaggeredGridLayoutManager(
                2,
                StaggeredGridLayoutManager.VERTICAL
            ).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
            }
        } else {
            LinearLayoutManager(requireActivity())
        }
    }

    private fun applySearchStringToUI() {
        overlayState.value = GiphyOverlayState.Loading
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    // Infinite scroll
    private inner class GiphyScrollListener : InfiniteScrollListener() {
        override fun onLoadMore(currentPage: Int) {
            @Suppress("UNCHECKED_CAST")
            val loader = LoaderManager.getInstance(this@GiphyFragment)
                .getLoader<List<GiphyImage>>(0) as? GiphyLoader ?: return

            viewLifecycleOwner.lifecycleScope.launch {
                val images = withContext(Dispatchers.IO) {
                    loader.loadPage(currentPage * GiphyLoader.PAGE_SIZE)
                }
                giphyAdapter.addImages(images.toMutableList())
            }
        }
    }
}