package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UsernameUtils
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUtils


@HiltViewModel(assistedFactory = GroupMembersViewModel.Factory::class)
class GroupMembersViewModel @AssistedInject constructor(
    @Assisted private val groupId: AccountId,
    @ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    proStatusManager: ProStatusManager,
    configFactory: ConfigFactoryProtocol,
    usernameUtils: UsernameUtils,
    avatarUtils: AvatarUtils
) : BaseGroupMembersViewModel(groupId, context, storage, usernameUtils, configFactory, avatarUtils, proStatusManager) {

    private val _navigationActions = Channel<Intent>()
    val navigationActions get() = _navigationActions.receiveAsFlow()

    @AssistedFactory
    interface Factory {
        fun create(groupId: AccountId): GroupMembersViewModel
    }

    fun onMemberClicked(accountId: AccountId) {
        viewModelScope.launch(Dispatchers.Default) {
            val address = Address.fromSerialized(accountId.hexString)
            val threadId = storage.getThreadId(address)

            val intent = Intent(
                context,
                ConversationActivityV2::class.java
            )
            intent.putExtra(ConversationActivityV2.ADDRESS, address)
            intent.putExtra(ConversationActivityV2.THREAD_ID, threadId)

            _navigationActions.send(intent)
        }
    }
}
