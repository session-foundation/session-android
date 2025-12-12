package org.thoughtcrime.securesms.conversation.v2.settings

import android.annotation.SuppressLint
import android.os.Parcelable
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import network.loki.messenger.BuildConfig
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.disappearingmessages.DisappearingMessagesViewModel
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.DisappearingMessagesScreen
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsDestination.*
import org.thoughtcrime.securesms.conversation.v2.settings.notification.NotificationSettingsScreen
import org.thoughtcrime.securesms.conversation.v2.settings.notification.NotificationSettingsViewModel
import org.thoughtcrime.securesms.groups.ManageGroupMembersViewModel
import org.thoughtcrime.securesms.groups.GroupMembersViewModel
import org.thoughtcrime.securesms.groups.InviteMembersViewModel
import org.thoughtcrime.securesms.groups.ManageGroupAdminsViewModel
import org.thoughtcrime.securesms.groups.PromoteMembersViewModel
import org.thoughtcrime.securesms.groups.compose.ManageGroupMembersScreen
import org.thoughtcrime.securesms.groups.compose.GroupMembersScreen
import org.thoughtcrime.securesms.groups.compose.InviteAccountIdScreen
import org.thoughtcrime.securesms.groups.compose.InviteContactsScreen
import org.thoughtcrime.securesms.groups.compose.ManageGroupAdminsScreen
import org.thoughtcrime.securesms.groups.compose.PromoteMembersScreen
import org.thoughtcrime.securesms.home.startconversation.newmessage.NewMessageViewModel
import org.thoughtcrime.securesms.home.startconversation.newmessage.State
import org.thoughtcrime.securesms.media.MediaOverviewScreen
import org.thoughtcrime.securesms.media.MediaOverviewViewModel
import org.thoughtcrime.securesms.ui.NavigationAction
import org.thoughtcrime.securesms.ui.ObserveAsEvents
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.handleIntent
import org.thoughtcrime.securesms.ui.horizontalSlideComposable

// Destinations
sealed interface ConversationSettingsDestination: Parcelable {
    @Serializable
    @Parcelize
    data object RouteConversationSettings: ConversationSettingsDestination

    @Serializable
    @Parcelize
    data class RouteGroupMembers private constructor(
        private val address: String
    ): ConversationSettingsDestination {
        constructor(groupAddress: Address.Group): this(groupAddress.address)

        val groupAddress: Address.Group get() = Address.Group(AccountId(address))
    }

    @Serializable
    @Parcelize
    data class RouteManageMembers private constructor(
        private val address: String
    ): ConversationSettingsDestination {
        constructor(groupAddress: Address.Group): this(groupAddress.address)

        val groupAddress: Address.Group get() = Address.Group(AccountId(address))
    }

    @Serializable
    @Parcelize
    data class RouteManageAdmins private constructor(
        private val address: String,
        val navigateToPromoteMembers: Boolean = false
    ) : ConversationSettingsDestination {
        constructor(groupAddress: Address.Group, navigateToPromoteMembers: Boolean = false) : this(
            groupAddress.address,
            navigateToPromoteMembers
        )

        val groupAddress: Address.Group get() = Address.Group(AccountId(address))
    }

    @Serializable
    @Parcelize
    data class RoutePromoteMembers(
        private val address: String
    ): ConversationSettingsDestination {
        constructor(groupAddress: Address.Group): this(groupAddress.address)

        val groupAddress: Address.Group get() = Address.Group(AccountId(address))
    }

    @Serializable
    @Parcelize
    data class RouteInviteToGroup private constructor(
        private val address: String,
        val excludingAccountIDs: List<String>
    ): ConversationSettingsDestination {
        constructor(groupAddress: Address.Group, excludingAccountIDs: List<String>)
            : this(groupAddress.address, excludingAccountIDs)

        val groupAddress: Address.Group get() = Address.Group(AccountId(address))
    }

    @Serializable
    @Parcelize
    data object RouteDisappearingMessages: ConversationSettingsDestination

    @Serializable
    @Parcelize
    data object RouteAllMedia: ConversationSettingsDestination

    @Serializable
    @Parcelize
    data object RouteNotifications: ConversationSettingsDestination

    @Serializable
    @Parcelize
    data class RouteInviteToCommunity(
        val communityUrl: String
    ): ConversationSettingsDestination

    @Serializable
    @Parcelize
    data class RouteInviteAccountIdToGroup private constructor(
        private val address: String,
        val excludingAccountIDs: List<String>
    ): ConversationSettingsDestination {
        constructor(groupAddress: Address.Group, excludingAccountIDs: List<String>)
        : this(groupAddress.address, excludingAccountIDs)

        val groupAddress: Address.Group get() = Address.Group(AccountId(address))
    }
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ConversationSettingsNavHost(
    address: Address.Conversable,
    startDestination: ConversationSettingsDestination = RouteConversationSettings,
    returnResult: (String, Boolean) -> Unit,
    onBack: () -> Unit
){
    SharedTransitionLayout {
        val navController = rememberNavController()

        val navigatorHolder: ConversationSettingsNavigatorHolder = hiltViewModel()
        val navigator: UINavigator<ConversationSettingsDestination> = navigatorHolder.navigator

        val handleBack: () -> Unit = {
            if (navController.previousBackStackEntry != null) {
                navController.navigateUp()
            } else {
                onBack() // Finish activity if at root
            }
        }

        ObserveAsEvents(flow = navigator.navigationActions) { action ->
            when (action) {
                is NavigationAction.Navigate<ConversationSettingsDestination> -> navController.navigate(
                    action.destination
                ) {
                    action.navOptions(this)
                }

                NavigationAction.NavigateUp -> handleBack()

                is NavigationAction.NavigateToIntent -> {
                    navController.handleIntent(action.intent)
                }

                is NavigationAction.ReturnResult -> {
                    returnResult(action.code, action.value)
                }

                else -> {}
            }
        }

        NavHost(navController = navController, startDestination = startDestination) {
            // Conversation Settings
            horizontalSlideComposable<RouteConversationSettings> {
                val viewModel =
                    hiltViewModel<ConversationSettingsViewModel, ConversationSettingsViewModel.Factory> { factory ->
                        factory.create(address, navigator)
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
                    onBack = onBack,
                )
            }

            // Group Members
            horizontalSlideComposable<RouteGroupMembers> { backStackEntry ->
                val data: RouteGroupMembers = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<GroupMembersViewModel, GroupMembersViewModel.Factory> { factory ->
                        factory.create(data.groupAddress)
                    }

                GroupMembersScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                )
            }
            // Edit Group
            horizontalSlideComposable<RouteManageMembers> { backStackEntry ->
                val data: RouteManageMembers = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<ManageGroupMembersViewModel, ManageGroupMembersViewModel.Factory> { factory ->
                        factory.create(data.groupAddress, navigator)
                    }

                ManageGroupMembersScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                )
            }

            // Manage group Admins
            horizontalSlideComposable<RouteManageAdmins> { backStackEntry ->
                val data: RouteManageAdmins = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<ManageGroupAdminsViewModel, ManageGroupAdminsViewModel.Factory> { factory ->
                        factory.create(data.groupAddress, navigator, data.navigateToPromoteMembers)
                    }

                ManageGroupAdminsScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                )
            }

            // Invite Contacts to group
            horizontalSlideComposable<RouteInviteToGroup> { backStackEntry ->
                val data: RouteInviteToGroup = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<InviteMembersViewModel, InviteMembersViewModel.Factory> { factory ->
                        factory.create(
                            groupAddress = data.groupAddress,
                            excludingAccountIDs = data.excludingAccountIDs.map(Address::fromSerialized).toSet()
                        )
                    }

                // grab a hold of manage group's VM
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(
                        RouteManageMembers(data.groupAddress)
                    )
                }
                val manageGroupMembersViewModel: ManageGroupMembersViewModel = hiltViewModel(parentEntry)

                InviteContactsScreen(
                    viewModel = viewModel,
                    onDoneClicked = { shareHistory ->
                        //send invites from the manage group screen
                        manageGroupMembersViewModel.onSendInviteClicked(viewModel.currentSelected, shareHistory)
                        handleBack()
                    },
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                    forCommunity = false
                )
            }

            // Invite Contacts to community
            horizontalSlideComposable<RouteInviteToCommunity> { backStackEntry ->
                val viewModel =
                    hiltViewModel<InviteMembersViewModel, InviteMembersViewModel.Factory> { factory ->
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
                        handleBack()
                    },
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                    forCommunity = true
                )
            }

            // Invite contacts using Account ID
            horizontalSlideComposable<RouteInviteAccountIdToGroup> { backStackEntry ->
                val data: RouteInviteAccountIdToGroup = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<InviteMembersViewModel, InviteMembersViewModel.Factory> { factory ->
                        factory.create(
                            groupAddress = data.groupAddress,
                            excludingAccountIDs = data.excludingAccountIDs.map(Address::fromSerialized).toSet()
                        )
                    }

                val newMessageViewModel = hiltViewModel<NewMessageViewModel>()
                val uiState by newMessageViewModel.state.collectAsState(State())

                // grab a hold of manage group's VM
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(
                        RouteManageMembers(data.groupAddress)
                    )
                }

                val manageGroupMembersViewModel: ManageGroupMembersViewModel = hiltViewModel(parentEntry)

                LaunchedEffect(Unit) {
                    newMessageViewModel.success.collect { success ->
                        viewModel.sendCommand(
                            InviteMembersViewModel.Commands.HandleAccountId(
                                address = success.address
                            )
                        )
                    }
                }

                InviteAccountIdScreen(
                    viewModel = viewModel,
                    state = uiState,
                    qrErrors = newMessageViewModel.qrErrors,
                    callbacks = newMessageViewModel,
                    onBack = { handleBack() },
                    onHelp = { newMessageViewModel.onCommand(NewMessageViewModel.Commands.ShowUrlDialog) },
                    onDismissHelpDialog = {
                        newMessageViewModel.onCommand(NewMessageViewModel.Commands.DismissUrlDialog)
                    },
                    onSendInvite = { shareHistory ->
                        manageGroupMembersViewModel.onCommand(
                            ManageGroupMembersViewModel.Commands.SendInvites(
                                address = viewModel.currentSelected,
                                shareHistory = shareHistory
                            )
                        )
                        handleBack()
                    },
                )
            }

            // Promote Members to group Admin
            horizontalSlideComposable<RoutePromoteMembers> { backStackEntry ->
                val data: RoutePromoteMembers = backStackEntry.toRoute()

                val viewModel =
                    hiltViewModel<PromoteMembersViewModel, PromoteMembersViewModel.Factory> { factory ->
                        factory.create(groupAddress = data.groupAddress)
                    }

                val parentEntry = remember(backStackEntry) {
                    navController.previousBackStackEntry ?: error("RouteManageAdmin not in backstack")
                }
                val manageGroupAdminsViewModel: ManageGroupAdminsViewModel = hiltViewModel(parentEntry)

                PromoteMembersScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                    onPromoteClicked = { selectedMembers ->
                        manageGroupAdminsViewModel.onSendPromotionsClicked(selectedMembers)
                        handleBack()
                    }
                )
            }

            // Disappearing Messages
            horizontalSlideComposable<RouteDisappearingMessages> {
                val viewModel: DisappearingMessagesViewModel =
                    hiltViewModel<DisappearingMessagesViewModel, DisappearingMessagesViewModel.Factory> { factory ->
                        factory.create(
                            address = address,
                            isNewConfigEnabled = ExpirationConfiguration.isNewConfigEnabled,
                            showDebugOptions = BuildConfig.BUILD_TYPE != "release",
                            navigator = navigator
                        )
                    }

                DisappearingMessagesScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        handleBack()
                    },
                )
            }

            // All Media
            horizontalSlideComposable<RouteAllMedia> {
                val viewModel =
                    hiltViewModel<MediaOverviewViewModel, MediaOverviewViewModel.Factory> { factory ->
                        factory.create(address)
                    }

                MediaOverviewScreen(
                    viewModel = viewModel,
                    onClose = dropUnlessResumed {
                        handleBack()
                    },
                )
            }

            // Notifications
            horizontalSlideComposable<RouteNotifications> {
                val viewModel =
                    hiltViewModel<NotificationSettingsViewModel, NotificationSettingsViewModel.Factory> { factory ->
                        factory.create(address)
                    }

                NotificationSettingsScreen(
                    viewModel = viewModel,
                    onBack = dropUnlessResumed {
                        handleBack()
                    }
                )
            }
        }
    }
}