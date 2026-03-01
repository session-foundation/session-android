package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v3.ConversationV3Destination
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel
import org.thoughtcrime.securesms.ui.components.ConversationAppBar
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.components.ConversationTopBarParamsProvider
import org.thoughtcrime.securesms.ui.components.ConversationTopBarPreviewParams
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationV3ViewModel,
    onBack: () -> Unit,
) {
    val conversationState by viewModel.uiState.collectAsStateWithLifecycle()
    val appBarData by viewModel.appBarData.collectAsStateWithLifecycle()

    Conversation(
        conversationState = conversationState,
        appBarData = appBarData,
        sendCommand = viewModel::onCommand,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun Conversation(
    conversationState: ConversationV3ViewModel.UIState,
    appBarData: ConversationAppBarData,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            ConversationAppBar(
                data = appBarData,
                onBackPressed = onBack,
                onCallPressed = {}, //todo ConvoV3 implement
                searchQuery = "", //todo ConvoV3 implement
                onSearchQueryChanged = {}, //todo ConvoV3 implement
                onSearchQueryClear = {}, //todo ConvoV3 implement
                onSearchCanceled = {}, //todo ConvoV3 implement
                onAvatarPressed = {
                    sendCommand(
                        ConversationV3ViewModel.Commands.GoTo(
                            ConversationV3Destination.RouteConversationSettings
                        )
                    )
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { paddings ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings)
                .consumeWindowInsets(paddings)
                .padding(
                    horizontal = LocalDimensions.current.spacing,
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

            Text("--- Conversation V3 WIP ---")
        }
    }
}

@Preview
@Composable
fun PreviewConversation(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors,
) {
    PreviewTheme(colors) {
        Conversation(
            conversationState = ConversationV3ViewModel.UIState(),
            appBarData = ConversationAppBarData(
                title ="Friendo",
                pagerData = emptyList(),
                showAvatar = true,
                showCall = true,
                showSearch = false,
                showProBadge = false,
                avatarUIData = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryBlue
                        ),
                    )
                )
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}