package org.thoughtcrime.securesms.mediasend.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import network.loki.messenger.R
import org.session.libsession.utilities.MediaTypes
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.Media.Companion.ALL_MEDIA_BUCKET_ID
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@Composable
fun MediaPickerItemScreen(
    viewModel: MediaSendViewModel,
    bucketId: String,
    title: String,
    onBack: () -> Unit,
    onMediaSelected: (Media) -> Unit, // navigate to send screen
) {
    val uiState = viewModel.uiState.collectAsState().value

    LaunchedEffect(bucketId) {
        viewModel.getMediaInBucket(bucketId) // triggers repository + updates uiState.bucketMedia
        viewModel.onItemPickerStarted()
    }

    MediaPickerItem(
        title = title,
        media = uiState.bucketMedia,
        selectedMedia = uiState.selectedMedia,
        canLongPress = uiState.canLongPress,
        showMultiSelectAction = !uiState.showCountButton,
        onBack = onBack,
        onStartMultiSelect = {
            viewModel.onMultiSelectStarted()
        },
        onToggleSelection = { nextSelected ->
            viewModel.onMediaSelected(nextSelected) // List<Media?>
        },
        onSinglePick = { media ->
            onMediaSelected(media)
        },
        isMultiSelect = uiState.isMultiSelect
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPickerItem(
    title: String,
    media: List<Media>,
    selectedMedia: List<Media>,
    canLongPress: Boolean,
    showMultiSelectAction: Boolean,
    onBack: () -> Unit,
    onStartMultiSelect: () -> Unit,
    onToggleSelection: (selectedMedia: Media) -> Unit,
    onSinglePick: (Media) -> Unit,
    isMultiSelect: Boolean = false
) {

    val itemWidth = 85.dp
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val columns = maxOf(1, (screenWidth / itemWidth).toInt())

    Scaffold(
        modifier = Modifier.background(LocalColors.current.background),
        topBar = {
            BackAppBar(
                title = title,
                onBack = onBack,
                actions = {
                    if (showMultiSelectAction) {
                        IconButton(
                            onClick = {
                                onStartMultiSelect()
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_images),
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .padding(padding)
                .padding(LocalDimensions.current.tinySpacing)
                .fillMaxSize()
                .background(LocalColors.current.background),
            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.tinySpacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.tinySpacing)
        ) {
            itemsIndexed(
                items = media,
                key = { _, item -> item.uri }   // ignore index for stable key
            ) { index, item ->
                val isSelected = selectedMedia.any { it.uri == item.uri }
                MediaPickerItemCell(
                    media = item,
                    isSelected = isSelected,
                    selectedIndex = selectedMedia.indexOfFirst { it.uri == item.uri },
                    isMultiSelect = isMultiSelect,
                    canLongPress = canLongPress,
                    showSelectionOn = isSelected,
                    onMediaChosen = { onSinglePick(it) },
                    onSelectionStarted = onStartMultiSelect,
                    onSelectionChanged = onToggleSelection,
                    qaTag = stringResource(R.string.qa_mediapicker_image_item) + "-${index}"
                )
            }
        }
    }
}


@Preview(name = "Picker - no selection")
@Composable
private fun Preview_MediaPickerItem_NoSelection() {
    val media = previewMediaList()
    MediaPickerItem(
        title = "Screenshots",
        media = media,
        selectedMedia = emptyList(),
        canLongPress = true,
        showMultiSelectAction = true,
        onBack = {},
        onStartMultiSelect = {},
        onToggleSelection = {},
        onSinglePick = {},
    )
}

@Preview(name = "Picker - multi-select with 2 selected")
@Composable
private fun Preview_MediaPickerItem_WithSelection() {
    val media = previewMediaList()
    val selected = listOf(media[1], media[4])

    MediaPickerItem(
        title = "Camera Roll",
        media = media,
        selectedMedia = selected,
        canLongPress = true,
        showMultiSelectAction = false,
        onBack = {},
        onStartMultiSelect = {},
        onToggleSelection = {},
        onSinglePick = {},
    )
}

private fun previewMediaList(): List<Media> {
    return (1..12).map { i ->
        Media(
            "content://preview/media/$i".toUri(),
            "preview_$i.jpg",
            MediaTypes.IMAGE_JPEG,
            /* date */ 0L,
            /* width */ 1080,
            /* height */ 1080,
            /* size */ 1234L,
            /* bucketId */ ALL_MEDIA_BUCKET_ID,
            /* caption */ null
        )
    }
}