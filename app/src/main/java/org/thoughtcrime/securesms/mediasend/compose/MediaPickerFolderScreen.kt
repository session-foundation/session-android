package org.thoughtcrime.securesms.mediasend.compose

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.mediasend.MediaFolder
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions

@Composable
fun MediaPickerFolderScreen(
    viewModel: MediaSendViewModel,
    onFolderClick: (MediaFolder) -> Unit,
    title: String,
    handleBack: () -> Unit,
    manageMediaAccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshFolders()
        viewModel.onFolderPickerStarted()
    }

    MediaPickerFolder(
        folders = uiState.folders,
        onFolderClick = onFolderClick,
        title = title,
        handleBack = handleBack,
        showManageMediaAccess = uiState.showManagePhotoAccess,
        manageMediaAccess = manageMediaAccess
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun MediaPickerFolder(
    folders: List<MediaFolder>,
    onFolderClick: (folder: MediaFolder) -> Unit,
    title: String,
    handleBack: () -> Unit,
    showManageMediaAccess: Boolean,
    manageMediaAccess: () -> Unit
) {

    // span logic: screenWidth / media_picker_folder_width
    val folderWidth = 175.dp
    val columns = maxOf(1, (LocalConfiguration.current.screenWidthDp.dp / folderWidth).toInt())

    Scaffold(
        topBar = {
            BackAppBar(
                title = title,
                onBack = handleBack,
                actions = {
                    if (showManageMediaAccess) {
                        IconButton(
                            onClick = {
                                manageMediaAccess()
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_plus),
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .padding(LocalDimensions.current.tinySpacing)
                    .fillMaxSize()
                    .background(LocalColors.current.background),
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.tinySpacing),
                verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.tinySpacing)
            ) {
                itemsIndexed(
                    items = folders,
                    key = { index, folder -> folder.bucketId }
                ) { index, folder ->
                    MediaFolderCell(
                        title = folder.title,
                        count = folder.itemCount,
                        thumbnailUri = folder.thumbnailUri,
                        onClick = { onFolderClick(folder) },
                        qaTag = stringResource( R.string.qa_mediapicker_folder_item) +"-$index"
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun MediaPickerFolderPreview() {
    MediaPickerFolder(
        folders = listOf(
            MediaFolder(
                title = "Camera",
                itemCount = 0,
                thumbnailUri = null,
                bucketId = "camera"
            ),
            MediaFolder(
                title = "Daily Bugle",
                itemCount = 122,
                thumbnailUri = null,
                bucketId = "daily_bugle"
            ),
            MediaFolder(
                title = "Screenshots",
                itemCount = 42,
                thumbnailUri = null,
                bucketId = "screenshots"
            )
        ),
        onFolderClick = {},
        title = "Folders",
        handleBack = {},
        showManageMediaAccess = true,
        manageMediaAccess = {}
    )
}