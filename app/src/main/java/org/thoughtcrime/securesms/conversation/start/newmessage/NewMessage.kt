package org.thoughtcrime.securesms.conversation.start.newmessage

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.LoadingArcOr
import org.thoughtcrime.securesms.ui.components.AppBarCloseIcon
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.BorderlessButtonWithIcon
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButton
import org.thoughtcrime.securesms.ui.components.QRScannerScreen
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.SessionTabRow
import org.thoughtcrime.securesms.ui.contentDescription
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

private val TITLES = listOf(R.string.accountIdEnter, R.string.qrScan)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewMessage(
    state: State,
    qrErrors: Flow<String> = emptyFlow(),
    callbacks: Callbacks = object: Callbacks {},
    onClose: () -> Unit = {},
    onBack: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val pagerState = rememberPagerState { TITLES.size }

    Column(modifier = Modifier.background(
        LocalColors.current.backgroundSecondary,
        shape = MaterialTheme.shapes.small
    )) {
        // `messageNew` is now a plurals string so get the singular version
        val context = LocalContext.current
        val newMessageTitleTxt:String = context.resources.getQuantityString(R.plurals.messageNew, 1, 1)

        BackAppBar(
            title = newMessageTitleTxt,
            backgroundColor = Color.Transparent, // transparent to show the rounded shape of the container
            onBack = onBack,
            actions = { AppBarCloseIcon(onClose = onClose) }
        )
        SessionTabRow(pagerState, TITLES)
        HorizontalPager(pagerState) {
            when (TITLES[it]) {
                R.string.accountIdEnter -> EnterAccountId(state, callbacks, onHelp)
                R.string.qrScan -> QRScannerScreen(qrErrors, onScan = callbacks::onScanQrCode)
            }
        }
    }
}

@Composable
private fun EnterAccountId(
    state: State,
    callbacks: Callbacks,
    onHelp: () -> Unit = {}
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
            // Input field
            SessionOutlinedTextField(
                text = state.newMessageIdOrOns,
                modifier = Modifier
                    .padding(horizontal = LocalDimensions.current.spacing)
                    .qaTag(stringResource(R.string.AccessibilityId_sessionIdInput)),
                placeholder = stringResource(R.string.accountIdOrOnsEnter),
                onChange = callbacks::onChange,
                onContinue = callbacks::onContinue,
                error = state.error?.string(),
                isTextErrorColor = state.isTextErrorColor
            )

            Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

            // Help button
            BorderlessButtonWithIcon(
                text = stringResource(R.string.messageNewDescriptionMobile),
                modifier = Modifier
                    .contentDescription(R.string.AccessibilityId_messageNewDescriptionMobile)
                    .padding(horizontal = LocalDimensions.current.mediumSpacing)
                    .fillMaxWidth(),
                style = LocalType.current.small,
                color = LocalColors.current.textSecondary,
                iconRes = R.drawable.ic_circle_help,
                onClick = onHelp
            )
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
            // Next button
            PrimaryOutlineButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .contentDescription(R.string.next),
                enabled = state.isNextButtonEnabled,
                onClick = callbacks::onContinue
            ) {
                LoadingArcOr(state.loading) {
                    Text(stringResource(R.string.next))
                }
            }
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

@Preview
@Composable
private fun PreviewNewMessage(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        NewMessage(State("z"))
    }
}
