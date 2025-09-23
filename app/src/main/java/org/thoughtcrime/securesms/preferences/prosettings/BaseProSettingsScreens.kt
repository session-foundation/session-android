package org.thoughtcrime.securesms.preferences.prosettings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.SessionProSettingsHeader
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.DangerFillButtonRect
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.DialogBg

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
    title: CharSequence? = null,
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
                text = annotatedStringResource(title),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.text,

            )
        }

        Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

        Cell {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(LocalDimensions.current.smallSpacing)
            ) {
                content()
            }
        }

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

/**
 * A reusable structure for Pro Settings screens for non originating steps
 */
@Composable
fun BaseNonOriginatingProSettingsScreen(
    disabled: Boolean,
    onBack: () -> Unit,
    buttonText: String,
    dangerButton: Boolean,
    onButtonClick: () -> Unit,
    headerTitle: CharSequence?,
    contentTitle: String?,
    contentDescription: CharSequence?,
    linkCellsInfo: String?,
    linkCells: List<NonOriginatingLinkCellData> = emptyList(),
) {
    BaseCellButtonProSettingsScreen(
        disabled = disabled,
        onBack = onBack,
        buttonText = buttonText,
        dangerButton = dangerButton,
        onButtonClick = onButtonClick,
        title = headerTitle,
    ){
        if (contentTitle != null) {
            Text(
                text = contentTitle,
                style = LocalType.current.h7,
                color = LocalColors.current.text,
            )
        }

        if (contentDescription != null) {
            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))
            Text(
                text = annotatedStringResource(contentDescription),
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )
        }

        if (linkCellsInfo != null) {
            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))
            Text(
                text = linkCellsInfo,
                style = LocalType.current.base,
                color = LocalColors.current.textSecondary,
            )
        }

        Spacer(Modifier.height(LocalDimensions.current.xsSpacing))

        linkCells.forEachIndexed { index, data ->
            if (index > 0) {
                Spacer(Modifier.height(LocalDimensions.current.xsSpacing))
            }
            NonOriginatingLinkCell(data)
        }
    }
}

@Composable
fun NonOriginatingLinkCell(
    data: NonOriginatingLinkCellData
) {
    DialogBg(
        bgColor = LocalColors.current.backgroundTertiary
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(LocalDimensions.current.smallSpacing),
            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            // icon
            Box(modifier = Modifier
                .background(
                    color = LocalColors.current.accent.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                )
                .padding(10.dp)
            ){
                Icon(
                    modifier = Modifier.align(Center)
                        .size(LocalDimensions.current.iconMedium),
                    painter = painterResource(id = data.iconRes),
                    tint = LocalColors.current.accent,
                    contentDescription = null
                )
            }

            // text content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = annotatedStringResource(data.title),
                    style = LocalType.current.base.bold(),
                    color = LocalColors.current.text,
                )

                Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

                Text(
                    text = annotatedStringResource(data.info),
                    style = LocalType.current.base,
                    color = LocalColors.current.text,
                )
            }
        }
    }
}


data class NonOriginatingLinkCellData(
    val title: CharSequence,
    val info: CharSequence,
    @DrawableRes val iconRes: Int,
    val onClick: (() -> Unit)? = null
)

@Preview
@Composable
private fun PreviewBaseNonOrig(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        BaseNonOriginatingProSettingsScreen(
            disabled = false,
            onBack = {},
            headerTitle = "This is a title",
            buttonText = "This is a button",
            dangerButton = false,
            onButtonClick = {},
            contentTitle = "This is a content title",
            contentDescription = "This is a content description",
            linkCellsInfo = "This is a link cells info",
            linkCells = listOf(
                NonOriginatingLinkCellData(
                    title = "This is a title",
                    info = "This is some info",
                    iconRes = R.drawable.ic_globe
                ),
                NonOriginatingLinkCellData(
                    title = "This is another title",
                    info = "This is some different info",
                    iconRes = R.drawable.ic_phone
                )
            )
        )
    }
}
