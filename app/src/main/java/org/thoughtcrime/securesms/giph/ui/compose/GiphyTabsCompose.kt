@file:JvmName("GiphyTabsCompose") // lets Java call attachComposeTabs(...)
package org.thoughtcrime.securesms.giph.ui.compose

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.ui.components.SessionTabRow

@Composable
fun GiphyTabsCompose(
    pager: ViewPager2,
    titles: List<Int>
) {
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