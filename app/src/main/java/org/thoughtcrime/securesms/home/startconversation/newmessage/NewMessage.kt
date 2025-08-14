package org.thoughtcrime.securesms.home.startconversation.newmessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.LoadingArcOr
import org.thoughtcrime.securesms.ui.components.AppBarCloseIcon
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.BorderlessButtonWithIcon
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.QRScannerScreen
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.SessionTabRow
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
            actions = { AppBarCloseIcon(onClose = onClose) },
            windowInsets = WindowInsets(0, 0, 0, 0), // Insets handled by the dialog
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
    Surface(color = LocalColors.current.backgroundSecondary) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.padding(vertical = LocalDimensions.current.spacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SessionOutlinedTextField(
                    text = state.newMessageIdOrOns,
                    modifier = Modifier
                        .padding(horizontal = LocalDimensions.current.spacing)
                        .qaTag(R.string.AccessibilityId_sessionIdInput),
                    placeholder = stringResource(R.string.accountIdOrOnsEnter),
                    onChange = callbacks::onChange,
                    onContinue = callbacks::onContinue,
                    error = state.error?.string(),
                    isTextErrorColor = state.isTextErrorColor
                )

                Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

                BorderlessButtonWithIcon(
                    text = stringResource(R.string.messageNewDescriptionMobile),
                    modifier = Modifier
                        .qaTag(R.string.AccessibilityId_messageNewDescriptionMobile)
                        .padding(horizontal = LocalDimensions.current.mediumSpacing)
                        .fillMaxWidth(),
                    style = LocalType.current.small,
                    color = LocalColors.current.textSecondary,
                    iconRes = R.drawable.ic_circle_help,
                    onClick = onHelp
                )
            }

            Spacer(Modifier.weight(1f).heightIn(min = LocalDimensions.current.smallSpacing))

            AccentOutlineButton(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = LocalDimensions.current.xlargeSpacing)
                    .padding(bottom = LocalDimensions.current.smallSpacing)
                    .fillMaxWidth()
                    .qaTag(R.string.next),
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

@Preview
@Composable
private fun PreviewNewMessage(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        NewMessage(State("z"))
    }
}
