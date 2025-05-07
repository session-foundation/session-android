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
import androidx.compose.foundation.selection.toggleable
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
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.SessionSwitch
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
        setOn = viewModel::setOn,
        isOn = viewModel.isOn.collectAsState().value,
        items = viewModel.alternativeIcons.collectAsState().value,
        onItemSelected = viewModel::onIconSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDisguiseSettings(
    items: List<AppDisguiseSettingsViewModel.IconAndName>,
    isOn: Boolean,
    setOn: (Boolean) -> Unit,
    onItemSelected: (String) -> Unit,
    onBack: () -> Unit,
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
            Text(
                stringResource(R.string.appIcon),
                style = LocalType.current.large,
                color = LocalColors.current.textSecondary
            )

            Cell {
                Row(
                    modifier = Modifier
                        .toggleable(value = isOn, onValueChange = setOn)
                        .padding(LocalDimensions.current.xsSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.appIconEnableIconAndName),
                        modifier = Modifier.weight(1f),
                        style = LocalType.current.large,
                        color = LocalColors.current.text
                    )

                    SessionSwitch(checked = isOn, onCheckedChange = null)
                }
            }

            BoxWithConstraints {
                // Calculate the number of columns based on the min width we want each column
                // to be.
                val minColumnWidth = LocalDimensions.current.xxsSpacing + ICON_ITEM_SIZE_DP.dp
                val numColumn =
                    (constraints.maxWidth / LocalDensity.current.run { minColumnWidth.toPx() }).toInt()
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
                                            onSelected = { onItemSelected(item.id) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        stringResource(R.string.appIconAndNameDescription),
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = LocalDimensions.current.smallSpacing),
                        style = LocalType.current.base,
                        color = LocalColors.current.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

        }
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

    val (path, bitmap) = remember(icon, resources, theme) {
        val drawable =
            ResourcesCompat.getDrawable(resources, icon, theme) as AdaptiveIconDrawable
        drawable.iconMask.asComposePath() to drawable.toBitmap().asImageBitmap()
    }

    val textColor = LocalColors.current.text
    val selectedBorderColor = LocalColors.current.textSecondary
    val density = LocalDensity.current
    val borderStroke = Stroke(density.run { 2.dp.toPx() })

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
            stringResource(name),
            textAlign = TextAlign.Center,
            style = LocalType.current.large,
            color = textColor,
        )
    }
}

@Preview
@Preview(device = Devices.TABLET)
@Composable
private fun AppDisguiseSettingsPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        AppDisguiseSettings(
            items = listOf(
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
                    id = "3",
                    icon = R.mipmap.ic_launcher_news,
                    name = R.string.appNameNews,
                    selected = true
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
            isOn = true,
            setOn = { },
            onItemSelected = { },
            onBack = { },
        )
    }
}
