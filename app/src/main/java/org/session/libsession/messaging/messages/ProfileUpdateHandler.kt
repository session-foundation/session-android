package org.session.libsession.messaging.messages

import com.google.protobuf.ByteString
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.BitSet
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.updateContact
import org.session.protos.SessionProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.model.RecipientSettings
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import org.thoughtcrime.securesms.util.DateUtils.Companion.toEpochSeconds
import java.time.Instant
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
    private val recipientDatabase: RecipientSettingsDatabase,
    private val blindIdMappingRepository: BlindMappingRepository,
    private val recipientRepository: RecipientRepository,
) {

    fun handleProfileUpdate(senderId: AccountId, updates: Updates, fromCommunity: BaseCommunityInfo?) {
        val unblinded = if (senderId.prefix?.isBlinded() == true && fromCommunity != null) {
            blindIdMappingRepository.getMapping(fromCommunity.baseUrl, Address.Blinded(senderId))
        } else {
            null
        }

        val senderAddress = senderId.toAddress()
        val sender = recipientRepository.getRecipientSync(senderAddress)

        if (sender.isSelf) {
            Log.d(TAG, "Ignoring profile update for ourselves")
            return
        }

        Log.d(TAG, "Handling profile update for $senderId")

        // If the sender has standard address (either as unblinded, or as is), we will check if
        // they are a contact and update their contact information accordingly.
        val standardSender = unblinded ?: (senderAddress as? Address.Standard)
        if (standardSender != null && (!updates.name.isNullOrBlank() || updates.pic != null)) {
            configFactory.withMutableUserConfigs { configs ->
                var shouldUpdate = false
                configs.contacts.updateContact(standardSender) {
                    shouldUpdate = shouldUpdateProfile(
                        lastUpdated = profileUpdatedEpochSeconds.secondsToInstant(),
                        newUpdateTime = updates.profileUpdateTime
                    )

                    if (shouldUpdate) {
                        if (updates.name != null) {
                            name = updates.name
                        }

                        if (updates.pic != null) {
                            profilePicture = updates.pic
                        }

                        if (updates.proFeatures != null) {
                            proFeatures = updates.proFeatures
                        }

                        if (updates.profileUpdateTime != null) {
                            profileUpdatedEpochSeconds = updates.profileUpdateTime.toEpochSeconds()
                        }
                        Log.d(TAG, "Updated contact profile for ${standardSender.debugString}")
                    } else {
                        Log.d(TAG, "Ignoring contact profile update for ${standardSender.debugString}, no changes detected")
                    }
                }

                if (shouldUpdate) {
                    configs.convoInfoVolatile.set(
                        configs.convoInfoVolatile.getOrConstructOneToOne(standardSender.accountId.hexString)
                            .copy(proProofInfo = updates.proProof)
                    )
                }
            }
        }

        // If we have a blinded address, we need to look at if we have a blinded contact to update
        if (senderAddress is Address.Blinded && (updates.pic != null || !updates.name.isNullOrBlank())) {
            configFactory.withMutableUserConfigs { configs ->
                val shouldUpdate = configs.contacts.getBlinded(senderAddress.blindedId.hexString)?.let { c ->
                    if (shouldUpdateProfile(
                        lastUpdated = c.profileUpdatedEpochSeconds.secondsToInstant(),
                        newUpdateTime = updates.profileUpdateTime
                    )) {
                        if (updates.pic != null) {
                            c.profilePic = updates.pic
                        }

                        if (updates.name != null) {
                            c.name = updates.name
                        }

                        if (updates.proFeatures != null) {
                            c.proFeatures = updates.proFeatures
                        }

                        if (updates.profileUpdateTime != null) {
                            c.profileUpdatedEpochSeconds = updates.profileUpdateTime.toEpochSeconds()
                        }

                        configs.contacts.setBlinded(c)
                        true
                    } else {
                        false
                    }
                } == true

                if (shouldUpdate) {
                    configs.convoInfoVolatile.set(
                        configs.convoInfoVolatile.getOrConstructedBlindedOneToOne(senderAddress.blindedId.hexString)
                            .copy(proProofInfo = updates.proProof)
                    )
                }
            }
        }


        // We'll always update both blinded/unblinded addresses in the recipient settings db,
        // as the user could delete the config and leave us no way to find the profile pic of
        // the sender.
        sequenceOf(senderAddress, unblinded)
            .filterNotNull()
            .forEach { address ->
                recipientDatabase.save(address) { r ->
                    if (shouldUpdateProfile(
                            lastUpdated = r.profileUpdated,
                            newUpdateTime = updates.profileUpdateTime
                        )) {
                        r.copy(
                            name = updates.name ?: r.name,
                            profilePic = updates.pic ?: r.profilePic,
                            blocksCommunityMessagesRequests = updates.blocksCommunityMessageRequests ?: r.blocksCommunityMessagesRequests,
                            proData = updates.proProof?.let {
                                RecipientSettings.ProData(
                                    info = it,
                                    features = updates.proFeatures ?: BitSet()
                                )
                            },
                        )
                    } else if (updates.blocksCommunityMessageRequests != null &&
                            r.blocksCommunityMessagesRequests != updates.blocksCommunityMessageRequests) {
                        r.copy(blocksCommunityMessagesRequests = updates.blocksCommunityMessageRequests)
                    } else {
                        r
                    }
                }
            }
    }

    /**
     * Determines if the profile should be updated based on the last updated time and the new update time.
     *
     * This function takes optional times because we need to deal with older versions of the app
     * where the updated time is not set.
     */
    private fun shouldUpdateProfile(
        lastUpdated: Instant?,
        newUpdateTime: Instant?
    ): Boolean {
        val lastUpdatedTimestamp = lastUpdated?.toEpochSeconds() ?: 0L
        val newUpdateTimestamp = newUpdateTime?.toEpochSeconds() ?: 0L

        return (lastUpdatedTimestamp == 0L && newUpdateTimestamp == 0L) ||
                (newUpdateTimestamp > lastUpdatedTimestamp)
    }

    class Updates private constructor(
        // Name to update, must be non-blank if provided.
        val name: String? = null,
        val pic: UserPic? = null,
        val proProof: Conversation.ProProofInfo? = null,
        val proFeatures: BitSet<ProProfileFeature>? = null,
        val blocksCommunityMessageRequests: Boolean? = null,
        val profileUpdateTime: Instant?,
    ) {
        init {
            check(name == null || name.isNotBlank()) {
                "Name must be non-blank if provided"
            }
        }

        companion object {
            fun create(content: SessionProtos.Content): Updates? {
                val profile: SessionProtos.LokiProfile
                val profilePicKey: ByteString?

                when {
                    content.hasDataMessage() && content.dataMessage.hasProfile() -> {
                        profile = content.dataMessage.profile
                        profilePicKey =
                            if (content.dataMessage.hasProfileKey()) content.dataMessage.profileKey else null
                    }

                    content.hasMessageRequestResponse() && content.messageRequestResponse.hasProfile() -> {
                        profile = content.messageRequestResponse.profile
                        profilePicKey =
                            if (content.messageRequestResponse.hasProfileKey()) content.messageRequestResponse.profileKey else null
                    }

                    else -> {
                        // No profile found, not updating.
                        // This is different from having an empty profile, which is a valid update.
                        return null
                    }
                }

                val pic = if (profile.hasProfilePicture()) {
                    if (!profile.profilePicture.isNullOrBlank() && profilePicKey != null &&
                        profilePicKey.size() in VALID_PROFILE_KEY_LENGTH) {
                        UserPic(
                            url = profile.profilePicture,
                            key = profilePicKey.toByteArray()
                        )
                    } else {
                        UserPic.DEFAULT // Clear the profile picture
                    }
                } else {
                    null // No update to profile picture
                }

                val name = if (profile.hasDisplayName()) profile.displayName else null
                val blocksCommunityMessageRequests = if (content.hasDataMessage() &&
                    content.dataMessage.hasBlocksCommunityMessageRequests()) {
                    content.dataMessage.blocksCommunityMessageRequests
                } else {
                    null
                }

                if (name == null && pic == null && blocksCommunityMessageRequests == null) {
                    // Nothing is updated..
                    return null
                }

                return Updates(
                    name = name,
                    pic = pic,
                    blocksCommunityMessageRequests = blocksCommunityMessageRequests,
                    profileUpdateTime = if (profile.hasLastUpdateSeconds()) {
                        Instant.ofEpochSecond(profile.lastUpdateSeconds)
                    } else {
                        null
                    }
                )
            }

        }
    }

    companion object {
        const val TAG = "ProfileUpdateHandler"

        const val MAX_PROFILE_NAME_LENGTH = 100

        private val VALID_PROFILE_KEY_LENGTH = listOf(16, 32)
    }
}