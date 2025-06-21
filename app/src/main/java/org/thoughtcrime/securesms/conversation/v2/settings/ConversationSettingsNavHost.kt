package org.thoughtcrime.securesms.conversation.v2.settings

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import network.loki.messenger.BuildConfig
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.disappearingmessages.DisappearingMessagesViewModel
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.DisappearingMessagesScreen
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteAllMedia
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteConversationSettings
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteDisappearingMessages
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteFullscreenAvatar
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteGroupMembers
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteInviteToCommunity
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteInviteToGroup
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteManageMembers
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.RouteNotifications
import org.thoughtcrime.securesms.conversation.v2.settings.notification.NotificationSettingsScreen
import org.thoughtcrime.securesms.conversation.v2.settings.notification.NotificationSettingsViewModel
import org.thoughtcrime.securesms.groups.EditGroupViewModel
import org.thoughtcrime.securesms.groups.GroupMembersViewModel
import org.thoughtcrime.securesms.groups.SelectContactsViewModel
import org.thoughtcrime.securesms.groups.compose.EditGroupScreen
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen
import org.thoughtcrime.securesms.groups.compose.GroupMinimumVersionBanner
import org.thoughtcrime.securesms.groups.compose.InviteContactsScreen
import org.thoughtcrime.securesms.media.MediaOverviewScreen
import org.thoughtcrime.securesms.media.MediaOverviewViewModel
import org.thoughtcrime.securesms.ui.ANIM_TIME
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.horizontalSlideComposable

// Destinations
@Serializable
sealed interface ConversationSettingsDestination {
    @Serializable
    data object RouteConversationSettings: ConversationSettingsDestination

    @Serializable
    data class RouteGroupMembers(
        val groupId: String
    ): ConversationSettingsDestination

    @Serializable
    data class RouteManageMembers(
        val groupId: String
    ): ConversationSettingsDestination

    @Serializable
    data class RouteInviteToGroup(
        val groupId: String,
        val excludingAccountIDs: List<String>
    ): ConversationSettingsDestination

    @Serializable
    data object RouteDisappearingMessages: ConversationSettingsDestination

    @Serializable
    data object RouteAllMedia: ConversationSettingsDestination

    @Serializable
    data object RouteFullscreenAvatar: ConversationSettingsDestination

    @Serializable
    data object RouteNotifications: ConversationSettingsDestination

    @Serializable
    data class RouteInviteToCommunity(
        val communityUrl: String
    ): ConversationSettingsDestination
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ConversationSettingsNavHost(
    threadId: Long,
    threadAddress: Address?,
    navigator: ConversationSettingsNavigator,
    returnResult: (String, Boolean) -> Unit,
    onBack: () -> Unit
){
    SharedTransitionLayout {
        val navController = rememberNavController()

        ObserveAsEvents(flow = navigator.navigationActions) { action ->
            when (action) {
                is NavigationAction.Navigate -> navController.navigate(
                    action.destination
                ) {
                    action.navOptions(this)
                }

                NavigationAction.NavigateUp -> navController.navigateUp()

                is NavigationAction.NavigateToIntent -> {
                    navController.context.startActivity(action.intent)
                }

                is NavigationAction.ReturnResult -> {
                    returnResult(action.code, action.value)
                }
            }
        }

        NavHost(navController = navController, startDestination = navigator.startDestination) {
            // Conversation Settings
            composable<RouteConversationSettings>(
                enterTransition = {
                    fadeIn(
                        animationSpec = tween(
                            ANIM_TIME, easing = LinearEasing
                        )
                    ) + slideIntoContainer(
                        animationSpec = tween(ANIM_TIME, easing = EaseIn),
                        towards = AnimatedContentTransitionScope.SlideDirection.Start
                    )
                },
                exitTransition = { // conditional exit - we don't want the slide for things like coming back from the fullscreen avatar
                    if (targetState.destination.hasRoute<RouteFullscreenAvatar>())
                        ExitTransition.None
                    else fadeOut(
                        animationSpec = tween(
                            ANIM_TIME, easing = LinearEasing
                        )
                    ) + slideOutOfContainer(
                        animationSpec = tween(ANIM_TIME, easing = EaseOut),
                        towards = AnimatedContentTransitionScope.SlideDirection.Start
                    )
                },
                popEnterTransition = { // conditional pop enter - we don't want the slide for things like coming back from the fullscreen avatar
                    if (initialState.destination.hasRoute<RouteFullscreenAvatar>())
                        EnterTransition.None
                    else fadeIn(
                        animationSpec = tween(
                            ANIM_TIME, easing = LinearEasing
                        )
                    ) + slideIntoContainer(
                        animationSpec = tween(ANIM_TIME, easing = EaseIn),
                        towards = AnimatedContentTransitionScope.SlideDirection.End
                    )
                },
                popExitTransition = {
                    fadeOut(
                        animationSpec = tween(
                            ANIM_TIME, easing = LinearEasing
                        )
                    ) + slideOutOfContainer(
                        animationSpec = tween(ANIM_TIME, easing = EaseOut),
                        towards = AnimatedContentTransitionScope.SlideDirection.End
                    )
                }
            ) {
                val viewModel =
                    hiltViewModel<ConversationSettingsViewModel, ConversationSettingsViewModel.Factory> { factory ->
                        factory.create(threadId)
                    }

                val lifecycleOwner = LocalLifecycleOwner.current

                // capture the moment we resume the settings page
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        viewModel.onResume()
                    }
                }

                ConversationSettingsScreen(
                    viewModel = viewModel,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this,
                    showFullscreenAvatar = {
                        navController.navigate(RouteFullscreenAvatar)
                    },
                    onBack = onBack,
                )
            }

            // Group Members
            horizontalSlideComposable<RouteGroupMembers> { backStackEntry ->
                val data: RouteGroupMembers = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<GroupMembersViewModel, GroupMembersViewModel.Factory> { factory ->
                        factory.create(AccountId(data.groupId))
                    }

                GroupMembersScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    },
                )
            }
            // Edit Group
            horizontalSlideComposable<RouteManageMembers> { backStackEntry ->
                val data: RouteManageMembers = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<EditGroupViewModel, EditGroupViewModel.Factory> { factory ->
                        factory.create(AccountId(data.groupId))
                    }

                EditGroupScreen(
                    viewModel = viewModel,
                    navigateToInviteContact = {
                        navController.navigate(
                            RouteInviteToGroup(
                                groupId = data.groupId,
                                excludingAccountIDs = viewModel.excludingAccountIDsFromContactSelection.toList()
                            )
                        )
                    },
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    },
                )
            }

            // Invite Contacts to group
            horizontalSlideComposable<RouteInviteToGroup> { backStackEntry ->
                val data: RouteInviteToGroup = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<SelectContactsViewModel, SelectContactsViewModel.Factory> { factory ->
                        factory.create(
                            excludingAccountIDs = data.excludingAccountIDs.map(::AccountId).toSet()
                        )
                    }

                // grab a hold of manage group's VM
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(
                        RouteManageMembers(data.groupId)
                    )
                }
                val editGroupViewModel: EditGroupViewModel = hiltViewModel(parentEntry)

                InviteContactsScreen(
                    viewModel = viewModel,
                    onDoneClicked = dropUnlessResumed {
                        //send invites from the manage group screen
                        editGroupViewModel.onContactSelected(viewModel.currentSelected)

                        navController.popBackStack()
                    },
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    },
                    banner = {
                        GroupMinimumVersionBanner()
                    }
                )
            }

            // Invite Contacts to community
            horizontalSlideComposable<RouteInviteToCommunity> { backStackEntry ->
                val viewModel =
                    hiltViewModel<SelectContactsViewModel, SelectContactsViewModel.Factory> { factory ->
                        factory.create()
                    }

                // grab a hold of settings' VM
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(
                        RouteConversationSettings
                    )
                }
                val settingsViewModel: ConversationSettingsViewModel = hiltViewModel(parentEntry)

                InviteContactsScreen(
                    viewModel = viewModel,
                    onDoneClicked = {
                        //send invites from the settings screen
                        settingsViewModel.inviteContactsToCommunity(viewModel.currentSelected)

                        // clear selected contacts
                        viewModel.clearSelection()
                    },
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    },
                )
            }

            // Disappearing Messages
            horizontalSlideComposable<RouteDisappearingMessages> {
                val viewModel: DisappearingMessagesViewModel =
                    hiltViewModel<DisappearingMessagesViewModel, DisappearingMessagesViewModel.Factory> { factory ->
                        factory.create(
                            threadId = threadId,
                            isNewConfigEnabled = ExpirationConfiguration.isNewConfigEnabled,
                            showDebugOptions = BuildConfig.DEBUG
                        )
                    }

                DisappearingMessagesScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    },
                )
            }

            // All Media
            horizontalSlideComposable<RouteAllMedia> {
                if (threadAddress == null) {
                    navController.popBackStack()
                    return@horizontalSlideComposable
                }

                val viewModel =
                    hiltViewModel<MediaOverviewViewModel, MediaOverviewViewModel.Factory> { factory ->
                        factory.create(threadAddress)
                    }

                MediaOverviewScreen(
                    viewModel = viewModel,
                    onClose = dropUnlessResumed {
                        navController.popBackStack()
                    },
                )
            }

            // Notifications
            horizontalSlideComposable<RouteNotifications> {
                val viewModel =
                    hiltViewModel<NotificationSettingsViewModel, NotificationSettingsViewModel.Factory> { factory ->
                        factory.create(threadId)
                    }

                NotificationSettingsScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    }
                )
            }

            // Fullscreen Avatar
            composable<RouteFullscreenAvatar> {
                // grab a hold of manage convo setting's VM
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(
                        RouteConversationSettings
                    )
                }
                val mainVM: ConversationSettingsViewModel = hiltViewModel(parentEntry)
                val data by mainVM.uiState.collectAsState()

                FullscreenAvatarScreen(
                    data = data.avatarUIData,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this,
                    onBack = dropUnlessResumed {
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}