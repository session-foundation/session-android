package org.thoughtcrime.securesms.preferences.prosettings

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogBg
import org.thoughtcrime.securesms.ui.SessionProSettingsHeader
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.DangerFillButtonRect
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold

/**
 * Base structure used in most Pro Settings screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseProSettingsScreen(
    disabled: Boolean,
    hideHomeAppBar: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    onBack: () -> Unit,
    onHeaderClick: (() -> Unit)? = null,
    extraHeaderContent: @Composable (() -> Unit)? = null,
    content: @Composable LazyItemScope.() -> Unit
){
    // Calculate scroll fraction
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 28.dp.toPx() } } // amount before the appbar gets fully opaque

    // raw fraction 0..1 derived from scrolling
    val rawFraction by remember {
        derivedStateOf {
            when {
                listState.layoutInfo.totalItemsCount == 0 -> 0f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset / thresholdPx).coerceIn(0f, 1f)
            }
        }
    }

    // easing + smoothing of fraction
    val easedFraction = remember(rawFraction) {
        FastOutSlowInEasing.transform(rawFraction)
    }

    // setting the appbar's bg alpha based on scroll
    val backgroundColor = LocalColors.current.background.copy(alpha = easedFraction)

    Scaffold(
        topBar = if(!hideHomeAppBar){{
                BackAppBar(
                    title = "",
                    backgroundColor = backgroundColor,
                    onBack = onBack,
                )
            }} else {{}},
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { paddings ->

        val layoutDirection = LocalLayoutDirection.current
        val safeInsetsPadding = PaddingValues(
            start = paddings.calculateStartPadding(layoutDirection) + LocalDimensions.current.spacing,
            end = paddings.calculateEndPadding(layoutDirection)+ LocalDimensions.current.spacing,
            top = (paddings.calculateTopPadding() - LocalDimensions.current.appBarHeight)
                    .coerceAtLeast(0.dp) + 46.dp,
            bottom = paddings.calculateBottomPadding() + LocalDimensions.current.spacing
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .consumeWindowInsets(paddings),
            state = listState,
            contentPadding = safeInsetsPadding,
            horizontalAlignment = CenterHorizontally
        ) {
            item {
                SessionProSettingsHeader(
                    disabled = disabled,
                    onClick = onHeaderClick,
                    extraContent = extraHeaderContent
                )
            }

            item { content() }
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
    buttonText: String?,
    dangerButton: Boolean,
    onButtonClick: () -> Unit,
    title: CharSequence? = null,
    content: @Composable LazyItemScope.() -> Unit
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

        if(buttonText != null) {
            if (dangerButton) {
                DangerFillButtonRect(
                    modifier = Modifier.fillMaxWidth()
                        .widthIn(max = LocalDimensions.current.maxContentWidth),
                    text = buttonText,
                    onClick = onButtonClick
                )
            } else {
                AccentFillButtonRect(
                    modifier = Modifier.fillMaxWidth()
                        .widthIn(max = LocalDimensions.current.maxContentWidth),
                    text = buttonText,
                    onClick = onButtonClick
                )
            }
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
    buttonText: String?,
    dangerButton: Boolean,
    onButtonClick: () -> Unit,
    headerTitle: CharSequence?,
    contentTitle: String?,
    contentDescription: CharSequence?,
    contentClick: (() -> Unit)? = null,
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
                modifier = Modifier.then(
                    // make the component clickable is there is an action
                    if (contentClick != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = contentClick
                    )
                    else Modifier
                ),
                text = annotatedStringResource(contentDescription),
                style = LocalType.current.base,
                color = LocalColors.current.text,
                inlineContent = inlineContentMap(
                    textSize = LocalType.current.base.fontSize,
                    imageColor = LocalColors.current.text,
                ),
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
