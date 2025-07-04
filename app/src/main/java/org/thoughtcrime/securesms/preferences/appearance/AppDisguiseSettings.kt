package org.thoughtcrime.securesms.preferences.appearance

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import kotlin.math.ceil
import kotlin.math.min

@Composable
fun AppDisguiseSettingsScreen(
    viewModel: AppDisguiseSettingsViewModel,
    onBack: () -> Unit
) {
    AppDisguiseSettings(
        onBack = onBack,
        items = viewModel.iconList.collectAsState().value,
        dialogState = viewModel.confirmDialogState.collectAsState().value,
        onCommand = viewModel::onCommand,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDisguiseSettings(
    items: List<AppDisguiseSettingsViewModel.IconAndName>,
    dialogState: AppDisguiseSettingsViewModel.ConfirmDialogState?,
    onBack: () -> Unit,
    onCommand: (AppDisguiseSettingsViewModel.Command) -> Unit,
) {
    Scaffold(
        topBar = {
            BackAppBar(title = stringResource(R.string.sessionAppearance), onBack = onBack)
        }
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .padding(LocalDimensions.current.smallSpacing)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
        ) {
            BoxWithConstraints {
                // Calculate the number of columns based on the min width we want each column
                // to be.
                val minColumnWidth = LocalDimensions.current.xxsSpacing + ICON_ITEM_SIZE_DP.dp
                val maxNumColumn =
                    (constraints.maxWidth / LocalDensity.current.run { minColumnWidth.toPx() }).toInt()

                // Make sure we fit all the items in the columns by trying each column size until
                // we find one that suits. When the column size gets down to 1, it will always fit :
                // n % 1 is always 0.
                val numColumn = (maxNumColumn downTo 1)
                    .first { items.size % it == 0 }

                val numRows = ceil(items.size.toFloat() / numColumn).toInt()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing),
                ) {
                    Text(
                        stringResource(R.string.appIconAndNameSelectionTitle),
                        style = LocalType.current.large,
                        color = LocalColors.current.textSecondary
                    )

                    Cell {
                        Column(
                            modifier = Modifier.padding(LocalDimensions.current.xsSpacing),
                            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
                        ) {
                            repeat(numRows) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    for (index in row * numColumn..<min(
                                        numColumn * (row + 1),
                                        items.size
                                    )) {
                                        val item = items[index]
                                        IconItem(
                                            icon = item.icon,
                                            name = item.name,
                                            selected = item.selected,
                                            onSelected = {
                                                onCommand(
                                                    AppDisguiseSettingsViewModel.Command.IconSelected(
                                                        item.id
                                                    )
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        stringResource(R.string.appIconAndNameSelectionDescription),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LocalDimensions.current.smallSpacing),
                        style = LocalType.current.base,
                        color = LocalColors.current.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (dialogState != null) {
        AlertDialog(
            onDismissRequest = { onCommand(AppDisguiseSettingsViewModel.Command.IconSelectDismissed) },
            text = Phrase.from(LocalContext.current, R.string.appIconAndNameChangeConfirmation)
                .put(APP_NAME_KEY, stringResource(R.string.app_name))
                .format()
                .toString(),
            title = stringResource(R.string.appIconAndNameChange),
            buttons = listOf(
                DialogButtonData(
                    text = GetString(R.string.closeApp),
                    color = LocalColors.current.danger,
                ) { onCommand(AppDisguiseSettingsViewModel.Command.IconSelectConfirmed(dialogState.id)) },
                DialogButtonData(text = GetString(R.string.cancel), dismissOnClick = true)
            )
        )
    }
}

private const val ICON_ITEM_SIZE_DP = 90

@Composable
private fun IconItem(
    @DrawableRes icon: Int,
    @StringRes name: Int,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val theme = LocalContext.current.theme

    val (path: Path, bitmap) = remember(icon, resources, theme) {
        val drawable = ResourcesCompat.getDrawable(resources, icon, theme)

        when(drawable){
            is AdaptiveIconDrawable -> drawable.iconMask.asComposePath() to drawable.toBitmap().asImageBitmap()
            else -> { // if the system does not support adaptive icons (like Huawei phones) default to a rectangle shape
                val bmp = drawable!!.toBitmap()
                Path().apply { addRect(Rect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())) } to bmp.asImageBitmap()
            }
        }
    }

    val textColor = LocalColors.current.text
    val selectedBorderColor = LocalColors.current.textSecondary
    val density = LocalDensity.current
    val borderStroke = Stroke(density.run { 2.dp.toPx() })
    val nameText = stringResource(name)

    Column(
        modifier = modifier
            .padding(LocalDimensions.current.xxxsSpacing),
        verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            BitmapPainter(bitmap),
            modifier = Modifier
                .size(ICON_ITEM_SIZE_DP.dp)
                .drawWithContent {
                    drawContent()
                    if (selected) {
                        val scaleX = size.width / path.getBounds().width
                        scale(scaleX, scaleX, pivot = Offset.Zero) {
                            drawPath(
                                path = path,
                                color = selectedBorderColor,
                                style = borderStroke
                            )
                        }
                    }
                }
                .qaTag("$nameText option")
                .selectable(
                    selected = selected,
                    onClick = onSelected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
                .padding(4.dp),
            contentDescription = null
        )

        Text(
            nameText,
            textAlign = TextAlign.Center,
            style = LocalType.current.large,
            color = textColor,
        )
    }
}

@Preview
@Preview(device = Devices.TABLET)
@Preview(widthDp = 486)
@Preview(widthDp = 300)
@Composable
private fun AppDisguiseSettingsPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        AppDisguiseSettings(
            items = listOf(
                AppDisguiseSettingsViewModel.IconAndName(
                    id = "3",
                    icon = R.mipmap.ic_launcher,
                    name = R.string.app_name,
                    selected = true
                ),
                AppDisguiseSettingsViewModel.IconAndName(
                    id = "1",
                    icon = R.mipmap.ic_launcher_weather,
                    name = R.string.appNameWeather,
                    selected = false
                ),
                AppDisguiseSettingsViewModel.IconAndName(
                    id = "2",
                    icon = R.mipmap.ic_launcher_stocks,
                    name = R.string.appNameStocks,
                    selected = false
                ),
                AppDisguiseSettingsViewModel.IconAndName(
                    id = "1",
                    icon = R.mipmap.ic_launcher_notes,
                    name = R.string.appNameNotes,
                    selected = false
                ),
                AppDisguiseSettingsViewModel.IconAndName(
                    id = "1",
                    icon = R.mipmap.ic_launcher_meetings,
                    name = R.string.appNameMeetingSE,
                    selected = false
                ),
                AppDisguiseSettingsViewModel.IconAndName(
                    id = "1",
                    icon = R.mipmap.ic_launcher_calculator,
                    name = R.string.appNameCalculator,
                    selected = false
                ),
            ),
            onBack = { },
            dialogState = null,
            onCommand = {}
        )
    }
}
