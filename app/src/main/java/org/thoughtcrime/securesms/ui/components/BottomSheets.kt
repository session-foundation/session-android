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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType


/**
 * The base bottom sheet with our app's styling
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BaseBottomSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit
){
    ModalBottomSheet(
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
) {
    val sheetState = rememberModalBottomSheetState()

    BaseBottomSheet(
        sheetState = sheetState,
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
private fun ActionSheetItem(
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
@Composable
fun ActionSheet(
    items: Collection<ActionSheetItemData>,
    onDismissRequest: () -> Unit
) {
    ActionSheet(
        options = items,
        onDismissRequest = onDismissRequest,
        onOptionClick = { it.onClick() },
        optionTitle = { it.title },
        optionIconRes = { it.iconRes },
        optionQaTag = { it.qaTag }
    )
}