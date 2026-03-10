package org.thoughtcrime.securesms.conversation.v3.compose.conversation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.thoughtcrime.securesms.conversation.v3.ConversationDataMapper.ConversationItem
import org.thoughtcrime.securesms.conversation.v3.ConversationV3Destination
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel.ConversationScrollState
import org.thoughtcrime.securesms.conversation.v3.compose.message.Message
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageLayout
import org.thoughtcrime.securesms.conversation.v3.compose.message.MessageViewData
import org.thoughtcrime.securesms.conversation.v3.compose.message.PreviewMessageData
import org.thoughtcrime.securesms.conversation.v3.compose.message.PreviewMessageData.textGroup
import org.thoughtcrime.securesms.conversation.v3.compose.message.ReactionItem
import org.thoughtcrime.securesms.conversation.v3.compose.message.ReactionViewState
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.AnimateFade
import org.thoughtcrime.securesms.ui.components.ConversationAppBar
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement
import kotlin.math.absoluteValue


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationV3ViewModel,
    switchConvoVersion: () -> Unit,
    onBack: () -> Unit,
) {
    val conversationState by viewModel.uiState.collectAsStateWithLifecycle()
    val appBarData by viewModel.appBarData.collectAsStateWithLifecycle()
    val conversationItems = viewModel.conversationItems.collectAsLazyPagingItems()

    Conversation(
        conversationState = conversationState,
        appBarData = appBarData,
        conversationItems = conversationItems,
        scrollEvent = viewModel.scrollEvent,
        sendCommand = viewModel::onCommand,
        switchConvoVersion = switchConvoVersion,
        onBack = onBack,
    )

    val dialogsState by viewModel.dialogsState.collectAsState()
    val inputBarDialogState by viewModel.inputBarStateDialogsState.collectAsState()

    ConversationV3Dialogs(
        dialogsState = dialogsState,
        inputBarDialogsState = inputBarDialogState,
        sendCommand = viewModel::onCommand,
        sendInputBarCommand = viewModel::onInputBarCommand,
        onPostUserProfileModalAction = {
            // this function is to perform logic once an action
            // has been taken in the UPM, like messaging a user
            // in this case we want to make sure the reaction dialog is dismissed
            //todo convov3 close reaction pager once it's added - once in compose we might have a better solution overall
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun Conversation(
    conversationState: ConversationV3ViewModel.UIState,
    appBarData: ConversationAppBarData,
    conversationItems: LazyPagingItems<ConversationItem>,
    scrollEvent: Flow<ConversationV3ViewModel.ScrollEvent>,
    sendCommand: (ConversationV3ViewModel.Commands) -> Unit,
    switchConvoVersion: () -> Unit,
    onBack: () -> Unit,
) {
    val listController = rememberConversationListState()

    // Single collector for all scroll events
    LaunchedEffect(Unit) {
        scrollEvent.collectLatest { event ->
            listController.handleScrollEvent(event, conversationItems)
        }
    }

    // Report scroll state to VM
    val scrollState by listController.lazyListState.asConversationScrollState()
    LaunchedEffect(scrollState) {
        sendCommand(ConversationV3ViewModel.Commands.OnScrollStateChanged(scrollState))
    }

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

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddings)
                    .consumeWindowInsets(paddings),
                reverseLayout = true,  // newest messages at the bottom
                contentPadding = PaddingValues(
                    horizontal = LocalDimensions.current.xsSpacing
                ),
                state = listController.lazyListState,
            ) {
                items(
                    count = conversationItems.itemCount,
                    key = conversationItems.itemKey { item ->
                        when (item) {
                            is ConversationItem.Message -> messageItemKey(item.data.id)
                            is ConversationItem.DateBreak -> "date_${item.date}_${item.messageId}"
                            is ConversationItem.UnreadMarker -> "unread"
                        }
                    },
                    contentType = conversationItems.itemContentType { item ->
                        when (item) {
                            is ConversationItem.Message -> 0
                            is ConversationItem.DateBreak -> 1
                            is ConversationItem.UnreadMarker -> 2
                        }
                    }
                ) { index ->
                    when (val item = conversationItems[index]) {
                        is ConversationItem.Message -> {
                            Message(
                                data = item.data,
                                highlight = listController.highlightKeyFor(item.data.id),
                                sendCommand = sendCommand,
                                onExpandText = { messageId, extraHeightPx ->
                                    listController.scrollForMessageExpansion(
                                        messageId = messageId,
                                        extraHeightPx = extraHeightPx,
                                        pagingItems = conversationItems,
                                    )
                                },
                            )
                        }

                        is ConversationItem.DateBreak -> ConversationDateBreak(date = item.date)

                        is ConversationItem.UnreadMarker -> ConversationUnreadBreak()
                        null -> Unit
                    }
                }

                if (conversationItems.loadState.append is LoadState.Loading) {
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

            // Scroll-to-bottom button
            val buttonLabel = conversationState.scrollToBottomButton
            AnimateFade (
                visible = buttonLabel != null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = LocalDimensions.current.spacing,
                        bottom = LocalDimensions.current.xlargeSpacing
                    ),
                fadeOutAnimationSpec = tween(durationMillis = 100, easing = FastOutLinearInEasing)
            ) {
                ScrollToBottomButton(
                    // buttonLabel is guaranteed non-null inside AnimatedVisibility when visible=true,
                    // but we provide a safe fallback anyway
                    unreadLabel = buttonLabel ?: "",
                    onClick = { sendCommand(ConversationV3ViewModel.Commands.ScrollToBottom) },
                )
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
            conversationItems = flowOf<PagingData<ConversationItem>>(
                PagingData.from(
                    data = listOf(
                        ConversationItem.Message(
                            MessageViewData(
                                id = MessageId(0, false),
                                displayName = "Toto",
                                layout = MessageLayout.OUTGOING,
                                contentGroups = textGroup()
                            )),
                        ConversationItem.Message(
                            MessageViewData(
                                id = MessageId(0, false),
                                displayName = "Toto",
                                avatar = PreviewMessageData.sampleAvatar,
                                layout = MessageLayout.INCOMING,
                                contentGroups = textGroup("I have lots of reactions - Closed"),
                                reactions = ReactionViewState(
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
                                )
                            ))
                    )
                )
            ).collectAsLazyPagingItems(),
            scrollEvent = flowOf(),
            sendCommand = {},
            switchConvoVersion = {},
            onBack = {},
        )
    }
}


@Composable
fun LazyListState.asConversationScrollState(nearBottomThresholdDp: Dp = 50.dp): State<ConversationScrollState> {
    val thresholdPx = with(LocalDensity.current) { nearBottomThresholdDp.roundToPx() }
    return remember {
        derivedStateOf {
            val info = layoutInfo

            // If nothing is laid out yet (initial load), treat as near-bottom
            // to avoid a flash of the scroll-to-bottom button.
            if (info.visibleItemsInfo.isEmpty() || info.totalItemsCount == 0) {
                return@derivedStateOf ConversationScrollState(
                    isNearBottom = true,
                    isFullyScrolled = true,
                    firstVisibleIndex = 0,
                    lastVisibleIndex = 0,
                    totalItemCount = 0,
                )
            }

            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val isNearBottom = bottomItem != null && bottomItem.offset.absoluteValue <= thresholdPx
            val isFullyScrolled = bottomItem != null && bottomItem.offset >= 0

            ConversationScrollState(
                isNearBottom = isNearBottom,
                isFullyScrolled = isFullyScrolled,
                firstVisibleIndex = info.visibleItemsInfo.first().index,
                lastVisibleIndex = info.visibleItemsInfo.last().index,
                totalItemCount = info.totalItemsCount,
            )
        }
    }
}
