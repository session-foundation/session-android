package org.thoughtcrime.securesms.home.startconversation

import android.annotation.SuppressLint
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.home.startconversation.community.JoinCommunityScreen
import org.thoughtcrime.securesms.home.startconversation.community.JoinCommunityViewModel
import org.thoughtcrime.securesms.home.startconversation.group.CreateGroupScreen
import org.thoughtcrime.securesms.home.startconversation.home.StartConversationScreen
import org.thoughtcrime.securesms.home.startconversation.invitefriend.InviteFriend
import org.thoughtcrime.securesms.home.startconversation.newmessage.NewMessage
import org.thoughtcrime.securesms.home.startconversation.newmessage.NewMessageViewModel
import org.thoughtcrime.securesms.home.startconversation.newmessage.State
import org.thoughtcrime.securesms.ui.NavigationAction
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.components.BaseBottomSheet
import org.thoughtcrime.securesms.ui.handleIntent
import org.thoughtcrime.securesms.ui.horizontalSlideComposable
import org.thoughtcrime.securesms.ui.theme.PreviewTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartConversationSheet(
    modifier: Modifier = Modifier,
    accountId: String,
    onDismissRequest: () -> Unit,
){
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val scope = rememberCoroutineScope()

    BaseBottomSheet(
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = null,
        onDismissRequest = onDismissRequest,
    ){
        BoxWithConstraints {
            val windowWidthDp = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp()
            }

            val isFullWidth = maxWidth >= windowWidthDp

            val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            val contentMod = if (isFullWidth) {
                Modifier.windowInsetsPadding(horizontalInsets)
            } else {
                Modifier
            }

            val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            val targetHeight = (this.maxHeight - topInset) * 0.94f // sheet should take up 94% of the height, without the staatus bar
            Box(
                modifier = contentMod.height(targetHeight),
                contentAlignment = Alignment.TopCenter
            ) {
                StartConversationNavHost(
                    accountId = accountId,
                    onClose = {
                        scope.launch {
                            sheetState.hide()
                            onDismissRequest()
                        }
                    }
                )
            }
        }
    }
}

// Destinations
sealed interface StartConversationDestination {
    @Serializable
    data object Home: StartConversationDestination

    @Serializable
    data object NewMessage: StartConversationDestination

    @Serializable
    data object CreateGroup: StartConversationDestination

    @Serializable
    data object JoinCommunity: StartConversationDestination

    @Serializable
    data object InviteFriend: StartConversationDestination
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StartConversationNavHost(
    accountId: String,
    onClose: () -> Unit
){
    val navController = rememberNavController()
    val navigator: UINavigator<StartConversationDestination> =
        retain { UINavigator() }

    ObserveAsEvents(flow = navigator.navigationActions) { action ->
        when (action) {
            is NavigationAction.Navigate -> navController.navigate(
                action.destination
            ) {
                action.navOptions(this)
            }

            NavigationAction.NavigateUp -> navController.navigateUp()

            is NavigationAction.NavigateToIntent -> {
                navController.handleIntent(action.intent)
            }

            else -> {}
        }
    }

    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = StartConversationDestination.Home) {
        // Home
        horizontalSlideComposable<StartConversationDestination.Home> {
            StartConversationScreen (
                accountId = accountId,
                onClose = onClose,
                navigateTo = {
                    scope.launch { navigator.navigate(it) }
                }
            )
        }

        // New Message
        horizontalSlideComposable<StartConversationDestination.NewMessage> {
            val viewModel = hiltViewModel<NewMessageViewModel>()
            val uiState by viewModel.state.collectAsState(State())

                LaunchedEffect(Unit) {
                    scope.launch {
                        viewModel.success.collect {
                            context.startActivity(
                                ConversationActivityV2.createIntent(
                                    context,
                                    address = it.address
                                )
                            )

                            onClose()
                        }
                    }
                }

                NewMessage(
                    uiState,
                    viewModel.qrErrors,
                    viewModel,
                    onBack = { scope.launch { navigator.navigateUp() } },
                    onClose = onClose,
                    onHelp = { viewModel.onCommand(NewMessageViewModel.Commands.ShowUrlDialog) }
                )
                if (uiState.showUrlDialog != null) {
                    OpenURLAlertDialog(
                        url = uiState.showUrlDialog!!,
                        onDismissRequest = { viewModel.onCommand(NewMessageViewModel.Commands.DismissUrlDialog) }
                    )
                }
            }

        // Create Group
        horizontalSlideComposable<StartConversationDestination.CreateGroup> {
            CreateGroupScreen(
                onNavigateToConversationScreen = { address ->
                    activity?.startActivity(
                        ConversationActivityV2.createIntent(activity, address)
                    )
                },
                onBack = { scope.launch { navigator.navigateUp() }},
                onClose = onClose,
                fromLegacyGroupId = null,
            )
        }

        // Join Community
        horizontalSlideComposable<StartConversationDestination.JoinCommunity> {
            val viewModel = hiltViewModel<JoinCommunityViewModel>()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit){
                scope.launch {
                    viewModel.uiEvents.collect {
                        when(it){
                            is JoinCommunityViewModel.UiEvent.NavigateToConversation -> {
                                onClose()
                                activity?.startActivity(ConversationActivityV2.createIntent(activity, it.address))
                            }
                        }
                    }
                }
            }

            JoinCommunityScreen(
                state = state,
                sendCommand = { viewModel.onCommand(it) },
                onBack = { scope.launch { navigator.navigateUp() }},
                onClose = onClose
            )
        }

        // Invite Friend
        horizontalSlideComposable<StartConversationDestination.InviteFriend> {
            InviteFriend(
                accountId = accountId,
                onBack = { scope.launch { navigator.navigateUp() }},
                onClose = onClose
            )
        }

    }
}

@Preview
@Composable
fun PreviewStartConversationSheet(){
    PreviewTheme {
        StartConversationSheet(
            accountId = "",
            onDismissRequest = {},
        )
    }
}