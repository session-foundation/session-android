package org.session.libsession.messaging.messages

import dagger.Lazy
import network.loki.messenger.BuildConfig
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.BlindedIdMappingDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
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
    private val blindedIdMappingDatabase: BlindedIdMappingDatabase,
    private val prefs: TextSecurePreferences,
    private val storage: Lazy<StorageProtocol>,
) {

    fun handleProfileUpdate(sender: Address, updates: Updates, communityServerPubKey: String?) {
        val senderAccountId = AccountId.fromStringOrNull(sender.address)
        if (senderAccountId == null) {
            Log.e(TAG, "Invalid sender address")
            return
        }

        handleProfileUpdate(senderAccountId, updates, communityServerPubKey)
    }

    fun handleProfileUpdate(sender: AccountId, updates: Updates, communityServerPubKey: String?) {
        if (sender.hexString == prefs.getLocalNumber() ||
            (communityServerPubKey != null && storage.get().getUserBlindedAccountId(communityServerPubKey) == sender)
        ) {
            Log.w(TAG, "Ignoring profile update for local number")
            return
        }


        if (updates.name.isNullOrBlank() &&
            updates.pic == null &&
            updates.acceptsCommunityRequests == null
        ) {
            Log.i(TAG, "No valid profile updated data provided for $sender")
            return
        }


        Log.d(TAG, "Handling profile update for $sender")

        // If the sender is blind, we need to figure out their real address first.
        val actualSender =
            if (sender.prefix == IdPrefix.BLINDED || sender.prefix == IdPrefix.BLINDEDV2) {
                blindedIdMappingDatabase.getBlindedIdMapping(sender.hexString)
                    .firstNotNullOfOrNull { it.accountId }
                    ?.let(AccountId::fromStringOrNull)
                    ?: sender
            } else {
                sender
            }

        if (actualSender.hexString == prefs.getLocalNumber()) {
            Log.w(TAG, "Ignoring profile update for local number: $actualSender")
            return
        }

        // First, if the user is a contact, update the config and that's all we need to do.
        val isExistingContact = actualSender.prefix == IdPrefix.STANDARD &&
                configFactory.withMutableUserConfigs { configs ->
                    val existingContact = configs.contacts.get(actualSender.hexString)
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

        // If the actual sender is still blinded or unknown contact, we need to update their
        // settings in the recipient database instead, as we don't have a place in the config
        // for them.
        if (actualSender.prefix == IdPrefix.BLINDED || actualSender.prefix == IdPrefix.BLINDEDV2 ||
            actualSender.prefix == IdPrefix.STANDARD
        ) {
            Log.d(TAG, "Updating recipient profile for $actualSender")
            recipientDatabase.updateProfile(
                Address.fromSerialized(actualSender.hexString),
                updates.name,
                updates.pic,
                updates.acceptsCommunityRequests
            )
            return
        }

        if (BuildConfig.DEBUG) {
            throw IllegalArgumentException("Unsupported profile update for sender: $sender")
        }

        Log.e(TAG, "Unsupported profile updating for sender: $sender")
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