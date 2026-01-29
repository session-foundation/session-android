package org.thoughtcrime.securesms.preferences.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasePreferenceScreens(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    title: String = "",
    listState: LazyListState? = null,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = title,
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { paddings ->
        LazyColumn(
            state = listState ?: rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .background(LocalColors.current.background)
                .padding(paddings),
            contentPadding = PaddingValues(LocalDimensions.current.smallSpacing),
            horizontalAlignment = CenterHorizontally
        ) {
            content()
        }
    }
}