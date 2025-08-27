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
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUtils


@HiltViewModel(assistedFactory = GroupMembersViewModel.Factory::class)
class GroupMembersViewModel @AssistedInject constructor(
    @Assisted private val address: Address.Group,
    @param:ApplicationContext private val context: Context,
    storage: StorageProtocol,
    configFactory: ConfigFactoryProtocol,
    proStatusManager: ProStatusManager,
    avatarUtils: AvatarUtils,
    recipientRepository: RecipientRepository,
) : BaseGroupMembersViewModel(address, context, storage, configFactory, avatarUtils, recipientRepository, proStatusManager) {

    private val _navigationActions = Channel<Intent>()
    val navigationActions get() = _navigationActions.receiveAsFlow()

    @AssistedFactory
    interface Factory {
        fun create(address: Address.Group): GroupMembersViewModel
    }

    fun onMemberClicked(accountId: AccountId) {
        viewModelScope.launch(Dispatchers.Default) {
            _navigationActions.send(ConversationActivityV2.createIntent(
                context, Address.Standard(accountId)
            ))
        }
    }
}
