package org.thoughtcrime.securesms.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.widget.Toast
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.isBlinded
import org.session.libsession.utilities.isCommunityInbox
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.toBlinded
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.pro.ProStatusManager

/**
 * Helper class to get the information required for the user profile modal
 */
class UserProfileUtils @AssistedInject constructor(
    @param:ApplicationContext private val context: Context,
    @Assisted private val userAddress: Address,
    @Assisted private val threadAddress: Address.Conversable,
    @Assisted private val scope: CoroutineScope,
    private val avatarUtils: AvatarUtils,
    private val proStatusManager: ProStatusManager,
    private val blindedIdMappingRepository: BlindMappingRepository,
    private val recipientRepository: RecipientRepository,
) {
    private val _userProfileModalData: MutableStateFlow<UserProfileModalData?> = MutableStateFlow(null)
    val userProfileModalData: StateFlow<UserProfileModalData?>
        get() = _userProfileModalData

    init {
        Log.d("UserProfileUtils", "init")

        scope.launch(Dispatchers.Default) {
            _userProfileModalData.update { getDefaultProfileData() }
        }
    }

    private suspend fun getDefaultProfileData(): UserProfileModalData {
        // An address that would
        val resolvedAddress = userAddress.toBlinded()
            ?.let { blindedIdMappingRepository.findMappings(it).firstOrNull()?.second ?: it }
            ?: userAddress

        val recipient = recipientRepository.getRecipient(resolvedAddress)

        // we apply the display rules from figma (the numbers being the number of characters):
        // - if the address is blinded (with a tooltip), display as 10...10
        // - if the address is a resolved blinded id (with a tooltip) 23 / 23 / 20
        // - for the rest: non blinded address which aren't from a community, break in 33 / 33
        val displayAddress: String
        val tooltipText: CharSequence?

        when {
            // Case 1: the resolved address is still blinded...
            resolvedAddress.isBlinded -> {
                displayAddress = "${resolvedAddress.address.take(10)}...${resolvedAddress.address.takeLast(10)}"
                tooltipText = context.getString(R.string.tooltipBlindedIdCommunities)
            }

            // Case 2: We successfully resolved a blinded id...
            !resolvedAddress.isBlinded && (userAddress.isBlinded || userAddress.isCommunityInbox) -> {
                displayAddress = "${resolvedAddress.address.substring(0, 23)}\n${resolvedAddress.address.substring(23, 46)}\n${resolvedAddress.address.substring(46)}"
                tooltipText = Phrase.from(context, R.string.tooltipAccountIdVisible)
                    .put(NAME_KEY, truncateName(recipient.displayName()))
                    .format()
            }

            // Case 3: The address is not blinded at all...
            else -> {
                displayAddress = "${userAddress.address.take(33)}\n${userAddress.address.takeLast(33)}"
                tooltipText = null
            }
        }

        // The conversation screen can not take a pure blinded address, it will have to be a
        // "Community inbox" address, so we encode it here..
        val messageAddress: Address.Conversable? = when (resolvedAddress) {
            is Address.Blinded -> {
                if (threadAddress is Address.Community) {
                    Address.CommunityBlindedId(
                        serverUrl = threadAddress.serverUrl,
                        blindedId = resolvedAddress
                    )
                } else {
                    null
                }
            }

            is Address.Conversable -> resolvedAddress
            is Address.Unknown -> null
        }

        return UserProfileModalData(
            name = if (recipient.isLocalNumber) context.getString(R.string.you) else recipient.displayName(),
            subtitle = (recipient.data as? RecipientData.Contact)?.nickname?.takeIf { it.isNotBlank() }?.let { "($it)" },
            avatarUIData = avatarUtils.getUIDataFromRecipient(recipient),
            showProBadge = proStatusManager.shouldShowProBadge(recipient.address),
            currentUserPro = proStatusManager.isCurrentUserPro(),
            rawAddress = recipient.address.address,
            displayAddress = displayAddress,
            threadAddress = threadAddress,
            isBlinded = recipient.address.isBlinded,
            tooltipText = tooltipText,
            enableMessage = !recipient.address.isBlinded || recipient.acceptsBlindedCommunityMessageRequests,
            expandedAvatar = false,
            showQR = false,
            showProCTA = false,
            messageAddress = messageAddress,
        )

    }

    private fun truncateName(name: String): String {
        return if (name.length > 10) {
            name.take(10) + "…"
        } else {
            name
        }
    }

    fun onCommand(command: UserProfileModalCommands){
        when(command){
            UserProfileModalCommands.ShowProCTA -> {
                _userProfileModalData.update { _userProfileModalData.value?.copy(showProCTA = true) }
            }

            UserProfileModalCommands.HideSessionProCTA -> {
                _userProfileModalData.update { _userProfileModalData.value?.copy(showProCTA = false) }
            }

            UserProfileModalCommands.ToggleQR -> {
                    _userProfileModalData.update {
                        _userProfileModalData.value?.let{
                            it.copy(showQR = !it.showQR)
                        }
                    }
            }

            UserProfileModalCommands.ToggleAvatarExpand -> {
                _userProfileModalData.update {
                    _userProfileModalData.value?.let{
                        it.copy(expandedAvatar = !it.expandedAvatar)
                    }
                }
            }

            UserProfileModalCommands.CopyAccountId -> {
                //todo we do this in a few places, should reuse the logic
                val accountID = userAddress.address
                val clip = ClipData.newPlainText("Account ID", accountID)
                val manager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(clip)
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @AssistedFactory
    interface UserProfileUtilsFactory {
        fun create(userAddress: Address, threadAddress: Address.Conversable, scope: CoroutineScope): UserProfileUtils
    }

}

data class UserProfileModalData(
    val name: String,
    val subtitle: String?,
    val showProBadge: Boolean,
    val currentUserPro: Boolean,
    val rawAddress: String,
    val displayAddress: String,
    val threadAddress: Address.Conversable,
    val isBlinded: Boolean,
    val tooltipText: CharSequence?,
    val enableMessage: Boolean,
    val messageAddress: Address.Conversable?, // The address to send to ConversationActivity
    val expandedAvatar: Boolean,
    val showQR: Boolean,
    val avatarUIData: AvatarUIData,
    val showProCTA: Boolean
)

sealed interface UserProfileModalCommands {
    object ShowProCTA: UserProfileModalCommands
    object HideSessionProCTA: UserProfileModalCommands
    object CopyAccountId: UserProfileModalCommands
    object ToggleAvatarExpand: UserProfileModalCommands
    object ToggleQR: UserProfileModalCommands
}