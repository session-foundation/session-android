package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.SessionProSettingsHeader
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.DangerFillButtonRect
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

/**
 * Base structure used in most Pro Settings screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseProSettingsScreen(
    disabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
){
    Scaffold(
        topBar = {
            BackAppBar(
                title = "",
                backgroundColor = Color.Transparent,
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddings ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddings.calculateTopPadding() - LocalDimensions.current.appBarHeight)
                .consumeWindowInsets(paddings)
                .padding(
                    horizontal = LocalDimensions.current.spacing,
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = CenterHorizontally
        ) {
            Spacer(Modifier.height(46.dp))

            SessionProSettingsHeader(
                disabled = disabled,
            )

            content()
        }
    }
}

/**
 * A reusable structure for Pro Settings screen that has the base layout
 * plus a cell content with a button at the bottom
 */
@Composable
fun BaseCellButtonProSettingsScreen(
    disabled: Boolean,
    onBack: () -> Unit,
    buttonText: String,
    dangerButton: Boolean,
    onButtonClick: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit
) {
    BaseProSettingsScreen(
        disabled = disabled,
        onBack = onBack,
    ) {
        Spacer(Modifier.height(LocalDimensions.current.spacing))

        if(!title.isNullOrEmpty()) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.text,

            )
        }

        Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

        Cell(content = content)

        Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

        if(dangerButton) {
            DangerFillButtonRect(
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = LocalDimensions.current.maxContentWidth),
                text = buttonText,
                onClick = {}
            )
        } else {
            AccentFillButtonRect(
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = LocalDimensions.current.maxContentWidth),
                text = buttonText,
                onClick = {}
            )
        }
    }
}

@Preview
@Composable
private fun PreviewBaseCellButton(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        BaseCellButtonProSettingsScreen(
            disabled = false,
            onBack = {},
            title = "This is a title",
            buttonText = "This is a button",
            dangerButton = true,
            onButtonClick = {},
            content = {
                Box(
                    modifier = Modifier.padding(LocalDimensions.current.smallSpacing)
                ) {
                    Text("This is a cell button content screen~")
                }
            }
        )
    }
}
