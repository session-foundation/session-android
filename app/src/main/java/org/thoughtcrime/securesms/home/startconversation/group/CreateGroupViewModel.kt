package org.thoughtcrime.securesms.home.startconversation.group

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.OpenGroupUrlParser
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.utilities.TextUtilities.textSizeInBytes
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.groups.SelectContactsViewModel
import org.thoughtcrime.securesms.links.LinkChecker
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.links.LinkType.CommunityLink.DisplayType.GROUP
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUtils


@HiltViewModel(assistedFactory = CreateGroupViewModel.Factory::class)
class CreateGroupViewModel @AssistedInject constructor(
    configFactory: ConfigFactory,
    @param:ApplicationContext private val appContext: Context,
    private val storage: StorageProtocol,
    private val groupManagerV2: GroupManagerV2,
    private val linkChecker: LinkChecker,
    private val openGroupManager: OpenGroupManager,
    avatarUtils: AvatarUtils,
    proStatusManager: ProStatusManager,
    groupDatabase: GroupDatabase,
    @Assisted createFromLegacyGroupId: String?,
    recipientRepository: RecipientRepository,
): SelectContactsViewModel(
    configFactory = configFactory,
    avatarUtils = avatarUtils,
    proStatusManager = proStatusManager,
    excludingAccountIDs = emptySet(),
    contactFiltering = SelectContactsViewModel.Factory.defaultFiltering,
    recipientRepository = recipientRepository,
    context = appContext
) {
    // Child view model to handle contact selection logic

    // Input: group name
    private val mutableGroupName = MutableStateFlow("")
    private val mutableGroupNameError = MutableStateFlow("")

    // Output: group name
    val groupName: StateFlow<String> get() = mutableGroupName
    val groupNameError: StateFlow<String> get() = mutableGroupNameError

    // Output: loading state
    private val mutableIsLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> get() = mutableIsLoading

    // Community dialog
    private val mutableUrlDialog = MutableStateFlow<LinkType?>(null)
    val urlDialog: StateFlow<LinkType?> get() = mutableUrlDialog

    // Events
    private val mutableEvents = MutableSharedFlow<CreateGroupEvent>()
    val events: SharedFlow<CreateGroupEvent> get() = mutableEvents

    init {
        // When a legacy group ID is given, fetch the group details and pre-fill the name and members
        createFromLegacyGroupId?.let { id ->
            mutableIsLoading.value = true
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    groupDatabase.getGroup(id)?.let { group ->
                        mutableGroupName.value = group.title
                        val myPublicKey = storage.getUserPublicKey()

                        val accountIDs = group.members
                            .asSequence()
                            .filter { it.toString() != myPublicKey }
                            .mapTo(mutableSetOf()) { Address.fromSerialized(it.toString()) }

                        selectAccountIDs(accountIDs)
                        setManuallyAddedContacts(accountIDs)
                    }
                } finally {
                    mutableIsLoading.value = false
                }
            }
        }
    }

    fun onCreateClicked() {
        viewModelScope.launch {
            val groupName = groupName.value.trim()
            if (groupName.isBlank()) {
                mutableGroupNameError.value = appContext.getString(R.string.groupNameEnterPlease)
                return@launch
            }

            // Special case: Check if someone entered a Community link
            // If so show a dialog
            val communityLink = linkChecker.check(groupName) as? LinkType.CommunityLink
            if (communityLink != null) {
                mutableUrlDialog.value = communityLink.copy(displayType = GROUP)
                return@launch
            }

            // validate name length (needs to be less than 100 bytes)
            if(groupName.textSizeInBytes() > ConfigFactory.MAX_NAME_BYTES){
                mutableGroupNameError.value = appContext.getString(R.string.groupNameEnterShorter)
                return@launch
            }


            val selected = currentSelected
            if (selected.isEmpty()) {
                mutableEvents.emit(CreateGroupEvent.Error(appContext.getString(R.string.groupCreateErrorNoMembers)))
                return@launch
            }

            mutableIsLoading.value = true

            val createResult = withContext(Dispatchers.Default) {
                runCatching {
                    groupManagerV2.createGroup(
                        groupName = groupName,
                        groupDescription = "",
                        members = selected.map { AccountId(it.toString()) }.toSet()
                    )
                }
            }

            when (val recipient = createResult.getOrNull()) {
                null -> {
                    mutableEvents.emit(CreateGroupEvent.Error(appContext.getString(R.string.groupErrorCreate)))

                }
                else -> {
                    mutableEvents.emit(CreateGroupEvent.NavigateToConversation(recipient.address as Address.Conversable))
                }
            }

            mutableIsLoading.value = false
        }
    }

    fun onGroupNameChanged(name: String) {
        mutableGroupName.value = name

        mutableGroupNameError.value = ""
    }

    fun onDismissUrlDialog(){
        mutableUrlDialog.value = null
    }

    fun openOrJoinCommunity(url: String) {
        val openGroup = try {
            OpenGroupUrlParser.parseUrl(url)
        } catch (_: OpenGroupUrlParser.Error) {
            Toast.makeText(appContext, R.string.communityEnterUrlErrorInvalidDescription, Toast.LENGTH_SHORT)
                .show()
            return
        }

        onDismissUrlDialog()

        viewModelScope.launch {
            try {
                openGroupManager.add(
                    server = openGroup.server,
                    room = openGroup.room,
                    publicKey = openGroup.serverPublicKey,
                )

                // after joining or if already joined, open the conversation
                val communityAddress = Address.Community(openGroup.server, openGroup.room)
                mutableEvents.emit(CreateGroupEvent.NavigateToConversation(communityAddress))

            } catch (e: Exception) {
                Log.e("CreateGroupViewModel", "Error joining community", e)
                Toast.makeText(appContext, R.string.communityErrorDescription, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(createFromLegacyGroupId: String?): CreateGroupViewModel
    }
}

sealed interface CreateGroupEvent {
    data class NavigateToConversation(val address: Address.Conversable): CreateGroupEvent

    data class Error(val message: String): CreateGroupEvent
}