package org.thoughtcrime.securesms.groups

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UsernameUtils
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.util.AvatarUtils


@HiltViewModel(assistedFactory = GroupMembersViewModel.Factory::class)
class GroupMembersViewModel @AssistedInject constructor(
    @Assisted private val groupId: AccountId,
    @ApplicationContext context: Context,
    storage: StorageProtocol,
    configFactory: ConfigFactoryProtocol,
    usernameUtils: UsernameUtils,
    avatarUtils: AvatarUtils
) : BaseGroupMembersViewModel(groupId, context, storage, usernameUtils, configFactory, avatarUtils) {

    @AssistedFactory
    interface Factory {
        fun create(groupId: AccountId): GroupMembersViewModel
    }
}
