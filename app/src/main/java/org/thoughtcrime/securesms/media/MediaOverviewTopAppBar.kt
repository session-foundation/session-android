package org.thoughtcrime.securesms.media

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.components.ActionAppBar
import org.thoughtcrime.securesms.ui.components.AppBarBackIcon
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.PreviewTheme

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MediaOverviewTopAppBar(
    selectionMode: Boolean,
    numSelected: Int,
    title: String,
    onBackClicked: () -> Unit,
    onSaveClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    onSelectAllClicked: () -> Unit,
    appBarScrollBehavior: TopAppBarScrollBehavior
) {
    ActionAppBar(
        title = title,
        singleLine = true,
        actionModeTitle = numSelected.toString(),
        navigationIcon = { AppBarBackIcon(onBack = onBackClicked) },
        scrollBehavior = appBarScrollBehavior,
        actionMode = selectionMode,
        actionModeActions = {
            IconButton(onClick = onSaveClicked) {
                Icon(
                    painterResource(R.drawable.ic_arrow_down_to_line),
                    contentDescription = stringResource(R.string.save),
                    tint = LocalColors.current.text,
                )
            }

            IconButton(onClick = onDeleteClicked) {
                Icon(
                    painterResource(R.drawable.ic_trash_2),
                    contentDescription = stringResource(R.string.delete),
                    tint = LocalColors.current.text,
                )
            }

            IconButton(onClick = onSelectAllClicked) {
                Icon(
                    painterResource(R.drawable.ic_baseline_select_all_24),
                    contentDescription = stringResource(R.string.selectAll),
                    tint = LocalColors.current.text,
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun PreviewMediaOverviewAppBar() {
    PreviewTheme {
        MediaOverviewTopAppBar(
            selectionMode = false,
            numSelected = 0,
            title = "Really long title asdlkajsdlkasjdlaskdjalskdjaslkj",
            onBackClicked = {},
            onSaveClicked = {},
            onDeleteClicked = {},
            onSelectAllClicked = {},
            appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
                rememberTopAppBarState()
            )
        )
    }
}