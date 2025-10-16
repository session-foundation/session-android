package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

private val TITLES = listOf(R.string.sessionRecoveryPassword, R.string.qrScan)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionTabRow(
    pagerState: PagerState,
    titles: List<Int>
) {
    val animationScope = rememberCoroutineScope()
    BaseSessionTabRow(
        selectedIndex = pagerState.currentPage,
        titles = titles,
        onTabClick = { i ->
            animationScope.launch { pagerState.animateScrollToPage(i) }
        }
    )
}

/** For ViewPager2 integration
 * I created this initially as a workaround for GiphyTabs,
 * which requires quite a lot of changes to be a fully composable screen.
 *
 * Also marked this for deletion once the screens are fully composable.
 * */
@Deprecated("To be deleted when screens that use viewpager2 are refactored to HorizontalPager")
@Composable
fun SessionTabRow(
    selectedIndex: Int,
    titles: List<Int>,
    onTabSelected: (Int) -> Unit
) {
    BaseSessionTabRow(
        selectedIndex = selectedIndex.coerceIn(0, titles.lastIndex),
        titles = titles,
        onTabClick = onTabSelected
    )
}

/** Shared implementation */
@Composable
private fun BaseSessionTabRow(
    selectedIndex: Int,
    titles: List<Int>,
    onTabClick: (Int) -> Unit
) {
    TabRow(
        containerColor = Color.Unspecified,
        selectedTabIndex = selectedIndex,
        contentColor = LocalColors.current.text,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = LocalColors.current.accent,
                height = LocalDimensions.current.indicatorHeight
            )
        },
        divider = { HorizontalDivider(color = LocalColors.current.borders) }
    ) {
        titles.forEachIndexed { i, it ->
            Tab(
                modifier = Modifier.heightIn(min = 48.dp),
                selected = i == selectedIndex,
                onClick = { onTabClick(i) },
                selectedContentColor = LocalColors.current.text,
                unselectedContentColor = LocalColors.current.text,
            ) {
                Text(
                    text = stringResource(id = it),
                    style = LocalType.current.h8
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
fun PreviewSessionTabRow(
        @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val pagerState = rememberPagerState { TITLES.size }
        SessionTabRow(pagerState = pagerState, titles = TITLES)
    }
}
