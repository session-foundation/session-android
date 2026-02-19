@file:JvmName("GiphyTabsCompose") // lets Java call attachComposeTabs(...)
package org.thoughtcrime.securesms.giph.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.viewpager2.widget.ViewPager2
import org.thoughtcrime.securesms.ui.components.SessionTabRow

@Composable
fun GiphyTabsCompose(
    pager: ViewPager2,
    titles: List<Int>
) {
    var selectedIndex by rememberSaveable {
        mutableIntStateOf(pager.currentItem.coerceIn(0, titles.lastIndex))
    }

    // Keep pager -> tabs selection in sync.
    DisposableEffect(pager) {
        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedIndex = position
            }
        }
        pager.registerOnPageChangeCallback(callback)
        onDispose { pager.unregisterOnPageChangeCallback(callback) }
    }

    // Tabs -> ViewPager2
    SessionTabRow(
        selectedIndex = selectedIndex,
        titles = titles,
        onTabSelected = { index ->
            if (index != pager.currentItem) pager.setCurrentItem(index, true)
        }
    )
}