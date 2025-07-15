package org.session.libsession.messaging.messages

import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.RecipientDatabase
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This class handles the profile updates coming from user's messages. The messages can be
 * from a 1to1, groups or community conversations.
 *
 * Although [handleProfileUpdate] takes an [Address] or [AccountId], this class can only handle
 * the profile updates for **users**, not groups or communities' profile, as they have very different
 * mechanisms and storage for their updates.
 */
@Singleton
class ProfileUpdateHandler @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val recipientDatabase: RecipientDatabase,
    private val prefs: TextSecurePreferences,
    private val blindIdMappingRepository: BlindMappingRepository,
) {

    fun handleProfileUpdate(sender: Address, updates: Updates, fromCommunity: BaseCommunityInfo?) {
        if (updates.name.isNullOrBlank() &&
            updates.pic == null &&
            updates.acceptsCommunityRequests == null
        ) {
            Log.i(TAG, "No valid profile updated data provided for $sender")
            return
        }

        // Unblind the sender if it's blinded and we have information to unblind it.
        val actualSender = if (IdPrefix.fromValue(sender.address)?.isBlinded() == true && fromCommunity != null) {
            blindIdMappingRepository.getMapping(fromCommunity.baseUrl, sender)
        } else {
            sender
        } ?: sender

        if (actualSender.address == prefs.getLocalNumber()) {
            Log.w(TAG, "Ignoring profile update for local number")
            return
        }

        val actualSenderIdPrefix = IdPrefix.fromValue(actualSender.address)

        if (actualSenderIdPrefix == null ||
            actualSenderIdPrefix !in EnumSet.of(IdPrefix.STANDARD, IdPrefix.BLINDED, IdPrefix.BLINDEDV2)) {
            Log.w(TAG, "Unsupported profile update for sender: $sender (actualSender: $actualSender)")
            return
        }

        Log.d(TAG, "Handling profile update for $sender")

        // First, if the user is a contact, update the config and that's all we need to do.
        val isExistingContact = actualSenderIdPrefix == IdPrefix.STANDARD &&
                configFactory.withMutableUserConfigs { configs ->
                    val existingContact = configs.contacts.get(actualSender.address)
                    if (existingContact != null) {
                        configs.contacts.set(
                            existingContact.copy(
                                name = updates.name ?: existingContact.name,
                                profilePicture = updates.pic ?: existingContact.profilePicture
                            )
                        )
                        true
                    } else {
                        false
                    }
                }

        if (isExistingContact && updates.acceptsCommunityRequests == null) {
            Log.d(TAG, "Updated existing contact profile for $sender (actualSender: $actualSender)")
            return
        }

        Log.d(TAG, "Updating recipient profile for $actualSender")
        recipientDatabase.updateProfile(
            actualSender,
            updates.name,
            updates.pic,
            updates.acceptsCommunityRequests
        )
    }

    data class Updates(
        val name: String? = null,
        val pic: UserPic? = null,
        val acceptsCommunityRequests: Boolean? = null,
    ) {
        constructor(
            name: String?,
            picUrl: String?,
            picKey: ByteArray?,
            acceptsCommunityRequests: Boolean?
        ) : this(
            name = name,
            pic = if (!picUrl.isNullOrBlank() && picKey != null && picKey.size in VALID_PROFILE_KEY_LENGTH) {
                UserPic(picUrl, picKey)
            } else {
                null
            }, acceptsCommunityRequests = acceptsCommunityRequests
        )
    }

    companion object {
        const val TAG = "ProfileUpdateHandler"

        const val MAX_PROFILE_NAME_LENGTH = 100

        private val VALID_PROFILE_KEY_LENGTH = listOf(16, 32)
    }
}