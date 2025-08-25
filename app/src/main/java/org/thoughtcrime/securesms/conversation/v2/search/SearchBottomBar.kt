package org.thoughtcrime.securesms.conversation.v2.search

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewSearchBottomBarBinding
import org.thoughtcrime.securesms.conversation.v2.search.SearchViewModel.Companion.MIN_QUERY_SIZE

class SearchBottomBar : LinearLayout {
    private lateinit var binding: ViewSearchBottomBarBinding
    private var eventListener: EventListener? = null

    // region Lifecycle
    constructor(context: Context) : super(context) { initialize() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initialize() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { initialize() }

    fun initialize() {
        binding = ViewSearchBottomBarBinding.inflate(LayoutInflater.from(context), this, true)
    }

    fun setData(position: Int, count: Int, searchQuery: String?) = with(binding) {
        binding.loading.visibility = GONE
        searchUp.setOnClickListener { v: View? ->
            if (eventListener != null) {
                eventListener!!.onSearchMoveUpPressed()
            }
        }
        searchDown.setOnClickListener { v: View? ->
            if (eventListener != null) {
                eventListener!!.onSearchMoveDownPressed()
            }
        }
        if (count > 0) { // we have results
            searchPosition.text = resources.getQuantityString(R.plurals.searchMatches, count, position + 1, count)
        } else if ( // we have a legitimate query but no results
            searchQuery != null &&
            searchQuery.length >= MIN_QUERY_SIZE &&
            count == 0
        ) {
            searchPosition.text = resources.getString(R.string.searchMatchesNone)
        } else { // we have no legitimate query yet
            searchPosition.text = ""
        }
        setViewEnabled(searchUp, position < count - 1)
        setViewEnabled(searchDown, position > 0)
    }

    fun showLoading() {
        binding.loading.visibility = VISIBLE
    }

    private fun setViewEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.25f
    }

    fun setEventListener(eventListener: EventListener?) {
        this.eventListener = eventListener
    }

    interface EventListener {
        fun onSearchMoveUpPressed()
        fun onSearchMoveDownPressed()
    }
}