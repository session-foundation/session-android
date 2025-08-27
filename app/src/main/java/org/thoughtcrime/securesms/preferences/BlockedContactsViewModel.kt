package org.thoughtcrime.securesms.preferences

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.SelectContactsViewModel
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUtils
import javax.inject.Inject

@HiltViewModel
class BlockedContactsViewModel @Inject constructor(
    configFactory: ConfigFactory,
    @param:ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    avatarUtils: AvatarUtils,
    proStatusManager: ProStatusManager,
    recipientRepository: RecipientRepository,
): SelectContactsViewModel(
    configFactory = configFactory,
    excludingAccountIDs = emptySet(),
    contactFiltering = { it.blocked },
    avatarUtils = avatarUtils,
    proStatusManager = proStatusManager,
    recipientRepository = recipientRepository,
) {
    private val _unblockDialog = MutableStateFlow(false)
    val unblockDialog: StateFlow<Boolean> = _unblockDialog

    fun unblock() {
        viewModelScope.launch {
            storage.setBlocked(
                recipients = currentSelected,
                isBlocked = false
            )

            clearSelection()
        }
    }

    fun onUnblockClicked(){
        _unblockDialog.value = true
    }

    fun hideUnblockDialog(){
        _unblockDialog.value = false
    }

    // Method to get the appropriate text to display when unblocking 1, 2, or several contacts
    fun getDialogText(): CharSequence {
        val contactsList = contacts.value
        val firstSelected = contactsList.first { it.address == currentSelected.elementAt(0) }

        return when (currentSelected.size) {
            // Note: We do not have to handle 0 because if no contacts are chosen then the unblock button is deactivated
            1 -> Phrase.from(context, R.string.blockUnblockName)
                .put(NAME_KEY, firstSelected.name)
                .format()
            2 -> Phrase.from(context, R.string.blockUnblockNameTwo)
                .put(NAME_KEY, firstSelected.name)
                .format()
            else -> {
                val othersCount = currentSelected.size - 1
                Phrase.from(context, R.string.blockUnblockNameMultiple)
                    .put(NAME_KEY, firstSelected.name)
                    .put(COUNT_KEY, othersCount)
                    .format()
            }
        }
    }
}
