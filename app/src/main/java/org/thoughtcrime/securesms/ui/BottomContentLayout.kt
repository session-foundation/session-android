package org.thoughtcrime.securesms.ui

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

/**
 * A layout that displays content at the bottom of the screen and caters for the keyboard pushing that content up
 */
@Composable
fun BottomContentLayout(
    mainContent: @Composable ColumnScope.() -> Unit,
    bottomContent: @Composable () -> Unit
) {
    // Get accurate IME height
    val keyboardHeight by keyboardHeightState()
    val isKeyboardVisible = keyboardHeight > 0.dp

    // Use a Column as the main container
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalColors.current.backgroundSecondary)
    ) {
        // Scrollable content area
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = LocalDimensions.current.spacing)
        ) {
            mainContent()
        }

        // Add extra space at the bottom to prevent content from being hidden by the button
        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        // Button container that responds to keyboard visibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LocalDimensions.current.xlargeSpacing,
                    end = LocalDimensions.current.xlargeSpacing,
                    bottom = LocalDimensions.current.smallSpacing
                )
                // Apply keyboard padding
                .then(
                    if (isKeyboardVisible) {
                        Modifier.padding(bottom = keyboardHeight)
                    } else {
                        Modifier.navigationBarsPadding()
                    }
                )
        ) {
            bottomContent()
        }
    }
}

@Composable
fun keyboardHeightState(): androidx.compose.runtime.State<Dp> {
    val view = LocalView.current
    val keyboardHeight = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val context = LocalContext.current

    DisposableEffect(view) {
        val rootView = view.rootView
        val rect = Rect()

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.height

            // Get the system window insets to account for status bar, navigation bar, etc.
            val windowInsets = ViewCompat.getRootWindowInsets(rootView)
            val systemBarsBottom = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

            // Calculate keyboard height taking into account the system bars
            val keyboardHeightPx = screenHeight - rect.bottom - systemBarsBottom

            // Only consider as keyboard if height is significant
            if (keyboardHeightPx > screenHeight * 0.15) {
                keyboardHeight.value = with(density) { keyboardHeightPx.toDp() }
            } else {
                keyboardHeight.value = 0.dp
            }
        }

        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return keyboardHeight
}