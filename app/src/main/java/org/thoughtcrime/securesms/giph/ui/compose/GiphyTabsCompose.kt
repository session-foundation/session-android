@file:JvmName("GiphyTabsCompose") // lets Java call attachComposeTabs(...)
package org.thoughtcrime.securesms.giph.ui.compose

import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.appcompat.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.ui.components.SessionTabRow
import org.thoughtcrime.securesms.ui.setThemedContent

fun attachComposeTabs(
    composeView: ComposeView,
    pager: ViewPager2,
    titles: List<Int>
) {
    composeView.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )

    composeView.setThemedContent {
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState { titles.size }

        // Keep pager -> tabs selection in sync.
        DisposableEffect(pager) {
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    scope.launch { pagerState.scrollToPage(position) }
                }
            }
            pager.registerOnPageChangeCallback(callback)
            onDispose { pager.unregisterOnPageChangeCallback(callback) }
        }

        SessionTabRow(
            pagerState = pagerState,
            titles = titles,
            onTabSelected = { index ->
                // Compose tab click -> advance ViewPager2 (and the indicator animates above)
                if (index != pager.currentItem) pager.setCurrentItem(index, true)
            }
        )
    }
}