@file:JvmName("GiphyTabsCompose") // lets Java call attachComposeTabs(...)
package org.thoughtcrime.securesms.giph.ui.compose

import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.appcompat.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2

fun attachComposeTabs(
    composeView: ComposeView,
    pager: ViewPager2,
    titles: List<String>
) {
    composeView.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )

    composeView.setContent {
        var selectedIndex by remember { mutableIntStateOf(pager.currentItem) }

        // Keep pager -> tabs selection in sync.
        DisposableEffect(pager) {
            val cb = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { selectedIndex = position }
            }
            pager.registerOnPageChangeCallback(cb)
            onDispose { pager.unregisterOnPageChangeCallback(cb) }
        }

        TabsRow(
            titles = titles,
            selectedIndex = selectedIndex,
            onSelect = { index ->
                if (index != pager.currentItem) pager.setCurrentItem(index, true)
                selectedIndex = index
            }
        )
    }
}

@Composable
private fun TabsRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val indicatorColor = colorFromAttrOr(
        R.attr.colorAccent,
        MaterialTheme.colorScheme.secondary
    )
    // text color that matches your toolbar’s foreground
    val appBarTextColor = colorFromAttrOr(
        android.R.attr.textColorPrimary,
        MaterialTheme.colorScheme.onPrimary
    )

    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        contentColor = appBarTextColor,
        divider = {},
        indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(positions[selectedIndex]),
                color = indicatorColor,
                height = 2.dp
            )
        }
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                selectedContentColor = appBarTextColor,
                unselectedContentColor = appBarTextColor.copy(alpha = 0.70f),
                text = { Text(title) }
            )
        }
    }
}

@Composable
private fun colorFromAttrOr(@AttrRes attrResId: Int, fallback: Color): Color {
    val context = LocalContext.current
    val tv = TypedValue()
    val resolved = context.theme.resolveAttribute(attrResId, tv, /* resolveRefs = */ true)
    if (!resolved) return fallback
    return if (tv.resourceId != 0) {
        Color(ContextCompat.getColor(context, tv.resourceId))
    } else {
        Color(tv.data)
    }
}