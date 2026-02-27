package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v3.ConversationV3Destination
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.ui.components.ConversationAppBar
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.components.ConversationTopBarParamsProvider
import org.thoughtcrime.securesms.ui.components.ConversationTopBarPreviewParams
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
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
    switchConvoVersion: () -> Unit,
    onBack: () -> Unit,
) {
    val conversationState by viewModel.uiState.collectAsStateWithLifecycle()
    val appBarData by viewModel.appBarData.collectAsStateWithLifecycle()
    val messages = viewModel.conversationMessages.collectAsLazyPagingItems()

    Conversation(
        conversationState = conversationState,
        appBarData = appBarData,
        messages = messages,
        sendCommand = viewModel::onCommand,
        switchConvoVersion = switchConvoVersion,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun Conversation(
    conversationState: ConversationV3ViewModel.UIState,
    appBarData: ConversationAppBarData,
    messages: LazyPagingItems<MessageViewData>,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit,
    switchConvoVersion: () -> Unit,
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
                switchConvoVersion = switchConvoVersion,
                onAvatarPressed = {
                    sendCommand(
                        ConversationV3ViewModel.Commands.GoTo(
                            ConversationV3Destination.RouteConversationSettings
                        )
                    )
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { paddings ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings)
                .consumeWindowInsets(paddings),
            reverseLayout = true,  // newest messages at the bottom
            contentPadding = PaddingValues(
                horizontal = LocalDimensions.current.xsSpacing
            ),
            state = rememberLazyListState(),
        ) {
            items(
                count = messages.itemCount,
                key = messages.itemKey { msg -> "${msg.id}_${msg.type}" }
            ) { index ->
                messages[index]?.let { message ->
                    Message(
                        data = message,
                    )
                }
            }

            // todo Convov3 do we want a loader for pagination?
            if (messages.loadState.append is LoadState.Loading) {
                item(key = "loading_append") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(
                            LocalDimensions.current.spacing
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        SmallCircularProgressIndicator()
                    }
                }
            }
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
            messages = flowOf<PagingData<MessageViewData>>(
                PagingData.from(
                    data = listOf(
                        MessageViewData(
                            id = MessageId(0, false),
                            author = "Toto",
                            type = PreviewMessageData.text()
                        ),
                        MessageViewData(
                            id = MessageId(0, false),
                            author = "Toto",
                            avatar = PreviewMessageData.sampleAvatar,
                            type = PreviewMessageData.text(
                                outgoing = false,
                                text = "I have lots of reactions - Closed"
                            ),
                            reactionsState = ReactionViewState(
                                reactions = listOf(
                                    ReactionItem("👍", 3, selected = true),
                                    ReactionItem("❤️", 12, selected = false),
                                    ReactionItem("😂", 1, selected = false),
                                    ReactionItem("😮", 5, selected = false),
                                    ReactionItem("😢", 2, selected = false),
                                    ReactionItem("🔥", 8, selected = false),
                                    ReactionItem("💕", 8, selected = false),
                                    ReactionItem("🐙", 8, selected = false),
                                    ReactionItem("✅", 8, selected = false),
                                ),
                                isExtended = false,
                                onReactionClick = {},
                                onReactionLongClick = {},
                                onShowMoreClick = {}
                            )
                        )
                    )
                )
            ).collectAsLazyPagingItems(),
            sendCommand = {},
            switchConvoVersion = {},
            onBack = {},
        )
    }
}