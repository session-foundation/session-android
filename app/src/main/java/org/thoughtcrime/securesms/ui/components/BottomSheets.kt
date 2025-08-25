package org.thoughtcrime.securesms.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors


/**
 * The base bottom sheet with our app's styling
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BaseBottomSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
){
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(
            topStart = LocalDimensions.current.xsSpacing,
            topEnd = LocalDimensions.current.xsSpacing
        ),
        dragHandle = dragHandle,
        containerColor = LocalColors.current.backgroundSecondary,
        content = content
    )
}


/**
 * A bottom sheet dialog that displays a list of options.
 *
 * @param options The list of options to display.
 * @param onDismissRequest Callback to be invoked when the dialog is to be dismissed.
 * @param onOptionClick Callback to be invoked when an option is clicked.
 * @param optionTitle A function that returns the title of an option.
 * @param optionIconRes A function that returns the icon resource of an option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ActionSheet(
    options: Collection<T>,
    onDismissRequest: () -> Unit,
    onOptionClick: (T) -> Unit,
    optionTitle: (T) -> String,
    optionQaTag: (T) -> Int,
    optionIconRes: (T) -> Int,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    BaseBottomSheet(
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = null,
        onDismissRequest = onDismissRequest
    ){
        for (option in options) {
            ActionSheetItem(
                text = optionTitle(option),
                leadingIcon = optionIconRes(option),
                qaTag = optionQaTag(option).takeIf { it != 0 }?.let { stringResource(it) },
                onClick = {
                    onOptionClick(option)
                    onDismissRequest()
                }
            )
        }
    }
}

@Composable
fun ActionSheetItem(
    leadingIcon: Int,
    text: String,
    qaTag: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(LocalDimensions.current.smallSpacing)
            .let { modifier ->
                qaTag?.let { modifier.qaTag(it) } ?: modifier
            }
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.spacing),
        verticalAlignment = CenterVertically,
    ) {
        Icon(
            painter = painterResource(leadingIcon),
            modifier = Modifier.size(LocalDimensions.current.iconMedium),
            tint = LocalColors.current.text,
            contentDescription = null
        )

        Text(
            modifier = Modifier.weight(1f),
            style = LocalType.current.large,
            text = text,
            textAlign = TextAlign.Start,
            color = LocalColors.current.text,
        )
    }
}


data class ActionSheetItemData(
    val title: String,
    @DrawableRes val iconRes: Int,
    @StringRes val qaTag: Int = 0,
    val onClick: () -> Unit,
)

/**
 * A convenience function to display a [ActionSheet] with a collection of [ActionSheetItemData].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    items: Collection<ActionSheetItemData>,
    sheetState: SheetState = rememberModalBottomSheetState(),
    onDismissRequest: () -> Unit
) {
    ActionSheet(
        options = items,
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
        onOptionClick = { it.onClick() },
        optionTitle = { it.title },
        optionIconRes = { it.iconRes },
        optionQaTag = { it.qaTag }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun ActionSheetPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
){
    PreviewTheme(colors) {
        val sheetState: SheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
        )

        ActionSheet(
            sheetState = sheetState,
            items = listOf(
                ActionSheetItemData(
                    title = "Option 1",
                    iconRes = R.drawable.ic_trash_2,
                    onClick = {}
                ),
                ActionSheetItemData(
                    title = "Option 2",
                    iconRes = R.drawable.ic_pencil,
                    onClick = {}
                ),
                ActionSheetItemData(
                    title = "Option 3",
                    iconRes = R.drawable.ic_arrow_down_to_line,
                    onClick = {}
                )
            ),
            onDismissRequest = {}
        )

    }
}