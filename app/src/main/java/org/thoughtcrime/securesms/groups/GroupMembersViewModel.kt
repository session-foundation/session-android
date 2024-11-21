package org.thoughtcrime.securesms.groups

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId


@HiltViewModel(assistedFactory = GroupMembersViewModel.Factory::class)
class GroupMembersViewModel @AssistedInject constructor(
    @Assisted private val groupId: AccountId,
    @ApplicationContext context: Context,
    storage: StorageProtocol,
    configFactory: ConfigFactoryProtocol
) : BaseGroupMembersViewModel(groupId, context, storage, configFactory) {

    @AssistedFactory
    interface Factory {
        fun create(groupId: AccountId): GroupMembersViewModel
    }
}
