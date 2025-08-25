package org.thoughtcrime.securesms.conversation.v2.settings.notification


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OptionsCard
import org.thoughtcrime.securesms.ui.OptionsCardData
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme


@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    NotificationSettings(
        state = state,
        onOptionSelected = viewModel::onOptionSelected,
        onSetClicked = viewModel::onSetClicked,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettings(
    state: NotificationSettingsViewModel.UiState,
    onOptionSelected: (Any) -> Unit,
    onSetClicked: suspend () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            BackAppBar(
                title = LocalContext.current.getString(R.string.sessionNotifications),
                onBack = onBack
            )
        },
    ) { paddings ->
        Column(
            modifier = Modifier.padding(paddings).consumeWindowInsets(paddings)
        ) {
            BottomFadingEdgeBox(modifier = Modifier.weight(1f)) { bottomContentPadding ->
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = LocalDimensions.current.spacing)
                ) {
                    Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

                    // notification options
                    if(state.notificationTypes != null) {
                        OptionsCard(state.notificationTypes, onOptionSelected)
                    }

                    // mute types
                    if(state.muteTypes != null) {
                        Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
                        OptionsCard(state.muteTypes, onOptionSelected)
                    }

                    Spacer(modifier = Modifier.height(bottomContentPadding))
                }
            }

            val coroutineScope = rememberCoroutineScope()
            AccentOutlineButton(
                stringResource(R.string.set),
                modifier = Modifier
                    .qaTag(R.string.AccessibilityId_setButton)
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = LocalDimensions.current.spacing),
                enabled = state.enableButton,
                onClick = {
                    coroutineScope.launch {
                        onSetClicked()
                        onBack() // leave screen once value is set
                    }
                }
            )
        }
    }
}

@Preview
@Composable
fun PreviewNotificationSettings(){
    PreviewTheme {
        NotificationSettings(
            state = NotificationSettingsViewModel.UiState(
                notificationTypes = OptionsCardData(
                        title = null,
                        options = listOf(
                            RadioOption(
                                value = NotificationSettingsViewModel.NotificationType.All,
                                title = GetString("All"),
                                selected = true
                            ),
                            RadioOption(
                                value = NotificationSettingsViewModel.NotificationType.All,
                                title = GetString("Mentions Only"),
                                selected = false
                            ),
                        )
                    ),
                muteTypes = OptionsCardData(
                        title = GetString("Other Options"),
                        options = listOf(
                            RadioOption(
                                value = Long.MAX_VALUE,
                                title = GetString("Something"),
                                selected = false
                            ),
                            RadioOption(
                                value = Long.MAX_VALUE,
                                title = GetString("Something Else"),
                                selected = false
                            ),
                        )
                ),
                enableButton = true
            ),
            onOptionSelected = {},
            onSetClicked = {},
            onBack = {}
        )
    }
}
