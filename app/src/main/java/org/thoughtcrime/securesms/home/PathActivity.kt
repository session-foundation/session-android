package org.thoughtcrime.securesms.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityPathBinding
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.utilities.NonTranslatableStringConstants.APP_NAME
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.GlowViewUtilities
import org.thoughtcrime.securesms.util.IP2Country
import org.thoughtcrime.securesms.util.PathDotView
import org.thoughtcrime.securesms.util.UiModeUtilities
import org.thoughtcrime.securesms.util.animateSizeChange
import org.thoughtcrime.securesms.util.disableClipping
import org.thoughtcrime.securesms.util.fadeIn
import org.thoughtcrime.securesms.util.fadeOut
import org.thoughtcrime.securesms.util.getAccentColor


class PathActivity : ScreenLockActionBarActivity() {
    private lateinit var binding: ActivityPathBinding
    private val broadcastReceivers = mutableListOf<BroadcastReceiver>()

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        binding = ActivityPathBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar!!.title = resources.getString(R.string.onionRoutingPath)

        // Substitute "Session" into the path description. Note: This is a non-translatable string.
        val txt = applicationContext.getSubbedString(R.string.onionRoutingPathDescription,APP_NAME_KEY to APP_NAME)
        binding.pathDescription.text = txt

        binding.pathRowsContainer.disableClipping()
        binding.learnMoreButton.setOnClickListener { learnMore() }
        update(false)
        registerObservers()

        IP2Country.configureIfNeeded(this)

        lifecycleScope.launch {
            // Check if the
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OnionRequestAPI.paths
                    .map { it.isEmpty() }
                    .distinctUntilChanged()
                    .collectLatest {
                        update(true)
                    }
            }
        }

        binding.pathScroll.doOnLayout {
            val child: View = binding.pathScroll.getChildAt(0)
            val isScrollable: Boolean = child.height > binding.pathScroll.height
            val params = binding.pathRowsContainer.layoutParams as FrameLayout.LayoutParams

            if(isScrollable){
                params.gravity = Gravity.CENTER_HORIZONTAL
            } else {
                params.gravity = Gravity.CENTER
            }

            binding.pathRowsContainer.layoutParams = params
        }
    }

    private fun registerObservers() {
        val onionRequestPathCountriesLoadedReceiver: BroadcastReceiver = object : BroadcastReceiver() {

            override fun onReceive(context: Context, intent: Intent) {
                handleOnionRequestPathCountriesLoaded()
            }
        }
        broadcastReceivers.add(onionRequestPathCountriesLoadedReceiver)
        LocalBroadcastManager.getInstance(this).registerReceiver(onionRequestPathCountriesLoadedReceiver, IntentFilter("onionRequestPathCountriesLoaded"))
    }

    override fun onDestroy() {
        for (receiver in broadcastReceivers) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        }
        super.onDestroy()
    }
    // endregion

    // region Updating

    private fun handleOnionRequestPathCountriesLoaded() { update(false) }

    private fun update(isAnimated: Boolean) {
        binding.pathRowsContainer.removeAllViews()

        val paths = OnionRequestAPI.paths.value
        if (paths.isNotEmpty()) {
            val path = paths.firstOrNull() ?: return finish()
            val dotAnimationRepeatInterval = path.count().toLong() * 1000 + 1000
            val pathRows = path.mapIndexed { index, snode ->
                val isGuardSnode = (OnionRequestAPI.guardSnodes.contains(snode))
                getPathRow(snode, LineView.Location.Middle, index.toLong() * 1000 + 2000, dotAnimationRepeatInterval, isGuardSnode)
            }
            val youRow = getPathRow(resources.getString(R.string.you), null, LineView.Location.Top, 1000, dotAnimationRepeatInterval)
            val destinationRow = getPathRow(resources.getString(R.string.onionRoutingPathDestination), null, LineView.Location.Bottom, path.count().toLong() * 1000 + 2000, dotAnimationRepeatInterval)
            val rows = listOf( youRow ) + pathRows + listOf( destinationRow )
            for (row in rows) {
                binding.pathRowsContainer.addView(row)
            }
            if (isAnimated) {
                binding.spinner.fadeOut()
            } else {
                binding.spinner.alpha = 0.0f
            }
        } else {
            if (isAnimated) {
                binding.spinner.fadeIn()
            } else {
                binding.spinner.alpha = 1.0f
            }
        }
    }
    // endregion

    // region General
    private fun getPathRow(title: String, subtitle: String?, location: LineView.Location, dotAnimationStartDelay: Long, dotAnimationRepeatInterval: Long): LinearLayout {
        val mainContainer = LinearLayout(this)
        mainContainer.orientation = LinearLayout.HORIZONTAL
        mainContainer.gravity = Gravity.CENTER_VERTICAL
        mainContainer.disableClipping()
        val mainContainerLayoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        mainContainer.layoutParams = mainContainerLayoutParams
        val lineView = LineView(this, location, dotAnimationStartDelay, dotAnimationRepeatInterval)
        val lineViewLayoutParams = LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.path_row_expanded_dot_size), resources.getDimensionPixelSize(R.dimen.path_row_height))
        lineView.layoutParams = lineViewLayoutParams
        mainContainer.addView(lineView)
        val titleTextView = TextView(this)
        titleTextView.setTextColor(getColorFromAttr(android.R.attr.textColorPrimary))
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.medium_font_size))
        titleTextView.text = title
        titleTextView.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
        val titleContainer = LinearLayout(this)
        titleContainer.orientation = LinearLayout.VERTICAL
        titleContainer.addView(titleTextView)
        val titleContainerLayoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        titleContainerLayoutParams.marginStart = resources.getDimensionPixelSize(R.dimen.large_spacing)
        titleContainer.layoutParams = titleContainerLayoutParams
        mainContainer.addView(titleContainer)
        if (subtitle != null) {
            val subtitleTextView = TextView(this)
            subtitleTextView.setTextColor(getColorFromAttr(android.R.attr.textColorPrimary))
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.small_font_size))
            subtitleTextView.text = subtitle
            subtitleTextView.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
            titleContainer.addView(subtitleTextView)
        }
        return mainContainer
    }

    private fun getPathRow(snode: Snode, location: LineView.Location, dotAnimationStartDelay: Long, dotAnimationRepeatInterval: Long, isGuardSnode: Boolean): LinearLayout {
        val title = if (isGuardSnode) resources.getString(R.string.onionRoutingPathEntryNode) else resources.getString(R.string.onionRoutingPathServiceNode)
        val subtitle = if (IP2Country.isInitialized) {
            IP2Country.shared.countryNamesCache[snode.ip] ?: resources.getString(R.string.resolving)
        } else {
            resources.getString(R.string.resolving)
        }
        return getPathRow(title, subtitle, location, dotAnimationStartDelay, dotAnimationRepeatInterval)
    }
    // endregion

    // region Interaction
    private fun learnMore() {
        try {
            val url = "https://getsession.org/faq/#onion-routing"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.communityEnterUrlErrorInvalid, Toast.LENGTH_SHORT).show()
        }
    }
    // endregion

    // region Line View
    private class LineView : RelativeLayout {
        private lateinit var location: Location
        private var dotAnimationStartDelay: Long = 0
        private var dotAnimationRepeatInterval: Long = 0
        private var job: Job? = null

        private val dotView by lazy {
            val result = PathDotView(context)
            result.setBackgroundResource(R.drawable.accent_dot)
            result.mainColor = context.getAccentColor()
            result
        }

        enum class Location {
            Top, Middle, Bottom
        }

        constructor(context: Context, location: Location, dotAnimationStartDelay: Long, dotAnimationRepeatInterval: Long) : super(context) {
            this.location = location
            this.dotAnimationStartDelay = dotAnimationStartDelay
            this.dotAnimationRepeatInterval = dotAnimationRepeatInterval
            setUpViewHierarchy()
        }

        constructor(context: Context) : super(context) {
            throw Exception("Use LineView(context:location:dotAnimationStartDelay:dotAnimationRepeatInterval:) instead.")
        }

        constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
            throw Exception("Use LineView(context:location:dotAnimationStartDelay:dotAnimationRepeatInterval:) instead.")
        }

        constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
            throw Exception("Use LineView(context:location:dotAnimationStartDelay:dotAnimationRepeatInterval:) instead.")
        }

        constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
            throw Exception("Use LineView(context:location:dotAnimationStartDelay:dotAnimationRepeatInterval:) instead.")
        }

        private fun setUpViewHierarchy() {
            disableClipping()
            val lineView = View(context)
            lineView.setBackgroundColor(context.getColorFromAttr(android.R.attr.textColorPrimary))
            val lineViewHeight = when (location) {
                Location.Top, Location.Bottom -> resources.getDimensionPixelSize(R.dimen.path_row_height) / 2
                Location.Middle -> resources.getDimensionPixelSize(R.dimen.path_row_height)
            }
            val lineViewLayoutParams = LayoutParams(1, lineViewHeight)
            when (location) {
                Location.Top -> lineViewLayoutParams.addRule(ALIGN_PARENT_BOTTOM)
                Location.Middle, Location.Bottom -> lineViewLayoutParams.addRule(ALIGN_PARENT_TOP)
            }
            lineViewLayoutParams.addRule(CENTER_HORIZONTAL)
            lineView.layoutParams = lineViewLayoutParams
            addView(lineView)
            val dotViewSize = resources.getDimensionPixelSize(R.dimen.path_row_dot_size)
            val dotViewLayoutParams = LayoutParams(dotViewSize, dotViewSize)
            dotViewLayoutParams.addRule(CENTER_IN_PARENT)
            dotView.layoutParams = dotViewLayoutParams
            addView(dotView)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startAnimation()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopAnimation()
        }

        private fun startAnimation() {
            job?.cancel()
            job = GlobalScope.launch {
                withContext(Dispatchers.Main) {
                    delay(dotAnimationStartDelay)
                    while (isActive) {
                        expand()
                        delay(EXPAND_ANIM_DELAY_MILLS)
                        collapse()
                        delay(dotAnimationRepeatInterval)
                    }
                }
            }
        }

        private fun stopAnimation() {
            job?.cancel()
            job = null
        }

        private fun expand() {
            dotView.animateSizeChange(R.dimen.path_row_dot_size, R.dimen.path_row_expanded_dot_size)
            @ColorRes val startColorID = if (UiModeUtilities.isDayUiMode(context)) R.color.transparent_black_30 else R.color.black
            val startColor = ContextCompat.getColor(context, startColorID)
            val endColor = context.getAccentColor()
            GlowViewUtilities.animateShadowColorChange(dotView, startColor, endColor)
        }

        private fun collapse() {
            dotView.animateSizeChange(R.dimen.path_row_expanded_dot_size, R.dimen.path_row_dot_size)
            @ColorRes val endColorID = if (UiModeUtilities.isDayUiMode(context)) R.color.transparent_black_30 else R.color.black
            val startColor = context.getAccentColor()
            val endColor = ContextCompat.getColor(context, endColorID)
            GlowViewUtilities.animateShadowColorChange(dotView, startColor, endColor)
        }

        companion object {
            private const val EXPAND_ANIM_DELAY_MILLS = 1000L
        }
    }
    // endregion
}