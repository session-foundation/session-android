package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import org.thoughtcrime.securesms.conversation.v2.utilities.TextUtilities.textSizeInBytes
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject


@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    configFactory: ConfigFactory,
    @ApplicationContext private val appContext: Context,
    private val storage: StorageProtocol,
    private val groupManagerV2: GroupManagerV2,
): ViewModel() {
    // Child view model to handle contact selection logic
    val selectContactsViewModel = SelectContactsViewModel(
        configFactory = configFactory,
        excludingAccountIDs = emptySet(),
        scope = viewModelScope,
        appContext = appContext,
    )

    // Input: group name
    private val mutableGroupName = MutableStateFlow("")
    private val mutableGroupNameError = MutableStateFlow("")

    // Output: group name
    val groupName: StateFlow<String> get() = mutableGroupName
    val groupNameError: StateFlow<String> get() = mutableGroupNameError

    // Output: loading state
    private val mutableIsLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> get() = mutableIsLoading

    // Events
    private val mutableEvents = MutableSharedFlow<CreateGroupEvent>()
    val events: SharedFlow<CreateGroupEvent> get() = mutableEvents

    fun onCreateClicked() {
        viewModelScope.launch {
            val groupName = groupName.value.trim()
            if (groupName.isBlank()) {
                mutableGroupNameError.value = appContext.getString(R.string.groupNameEnterPlease)
                return@launch
            }

            // validate name length (needs to be less than 100 bytes)
            if(groupName.textSizeInBytes() > MAX_GROUP_NAME_BYTES){
                mutableGroupNameError.value = appContext.getString(R.string.groupNameEnterShorter)
                return@launch
            }


            val selected = selectContactsViewModel.currentSelected
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
                        members = selected
                    )
                }
            }

            when (val recipient = createResult.getOrNull()) {
                null -> {
                    mutableEvents.emit(CreateGroupEvent.Error(appContext.getString(R.string.groupErrorCreate)))

                }
                else -> {
                    val threadId = withContext(Dispatchers.Default) { storage.getOrCreateThreadIdFor(recipient.address) }
                    mutableEvents.emit(CreateGroupEvent.NavigateToConversation(threadId))
                }
            }

            mutableIsLoading.value = false
        }
    }

    fun onGroupNameChanged(name: String) {
        mutableGroupName.value = name

        mutableGroupNameError.value = ""
    }
}

sealed interface CreateGroupEvent {
    data class NavigateToConversation(val threadID: Long): CreateGroupEvent

    data class Error(val message: String): CreateGroupEvent
}