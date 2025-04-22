package org.thoughtcrime.securesms.configs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_PINNED
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.ReadableUserProfile
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.Contact
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.UserPic
import network.loki.messenger.libsession_util.util.afterSend
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.BackgroundGroupAddJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.messaging.sending_receiving.pollers.LegacyClosedGroupPollerV2
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.SSKEnvironment.ProfileManagerProtocol.Companion.NAME_PADDED_LENGTH
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.groups.ClosedGroupManager
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.sskenvironment.ProfileManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "ConfigToDatabaseSync"

/**
 * This class is responsible for syncing config system's data into the database.
 *
 * @see ConfigUploader For upload config system data into swarm automagically.
 */
class ConfigToDatabaseSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val threadDatabase: ThreadDatabase,
    private val recipientDatabase: RecipientDatabase,
    private val clock: SnodeClock,
    private val profileManager: ProfileManager,
    private val preferences: TextSecurePreferences,
    private val conversationRepository: ConversationRepository,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val legacyClosedGroupPollerV2: LegacyClosedGroupPollerV2,
) {
    init {
        if (!preferences.migratedToGroupV2Config) {
            preferences.migratedToGroupV2Config = true

            GlobalScope.launch(Dispatchers.Default) {
                for (configType in UserConfigType.entries) {
                    syncUserConfigs(configType, null)
                }
            }
        }
    }

    fun syncGroupConfigs(groupId: AccountId) {
        val info = configFactory.withGroupConfigs(groupId) {
            UpdateGroupInfo(it.groupInfo)
        }

        updateGroup(info)
    }

    fun syncUserConfigs(userConfigType: UserConfigType, updateTimestamp: Long?) {
        val configUpdate = configFactory.withUserConfigs { configs ->
            when (userConfigType) {
                UserConfigType.USER_PROFILE -> UpdateUserInfo(configs.userProfile)
                UserConfigType.USER_GROUPS -> UpdateUserGroupsInfo(configs.userGroups)
                UserConfigType.CONTACTS -> UpdateContacts(configs.contacts.all())
                UserConfigType.CONVO_INFO_VOLATILE -> UpdateConvoVolatile(configs.convoInfoVolatile.all())
            }
        }

        when (configUpdate) {
            is UpdateUserInfo -> updateUser(configUpdate, updateTimestamp)
            is UpdateUserGroupsInfo -> updateUserGroups(configUpdate, updateTimestamp)
            is UpdateContacts -> updateContacts(configUpdate, updateTimestamp)
            is UpdateConvoVolatile -> updateConvoVolatile(configUpdate)
            else -> error("Unknown config update type: $configUpdate")
        }
    }

    private data class UpdateUserInfo(
        val name: String?,
        val userPic: UserPic,
        val ntsPriority: Long,
        val ntsExpiry: ExpiryMode
    ) {
        constructor(profile: ReadableUserProfile) : this(
            name = profile.getName(),
            userPic = profile.getPic(),
            ntsPriority = profile.getNtsPriority(),
            ntsExpiry = profile.getNtsExpiry()
        )
    }

    private fun updateUser(userProfile: UpdateUserInfo, messageTimestamp: Long?) {
        val userPublicKey = storage.getUserPublicKey() ?: return
        // would love to get rid of recipient and context from this
        val recipient = Recipient.from(context, fromSerialized(userPublicKey), false)

        // Update profile name
        userProfile.name?.takeUnless { it.isEmpty() }?.truncate(NAME_PADDED_LENGTH)?.let {
            preferences.setProfileName(it)
            profileManager.setName(context, recipient, it)
        }

        // Update profile picture
        if (userProfile.userPic == UserPic.DEFAULT) {
            storage.clearUserPic(clearConfig = false)
        } else if (userProfile.userPic.key.data.isNotEmpty() && userProfile.userPic.url.isNotEmpty()
            && preferences.getProfilePictureURL() != userProfile.userPic.url
        ) {
            storage.setUserProfilePicture(userProfile.userPic.url, userProfile.userPic.key.data)
        }

        if (userProfile.ntsPriority == PRIORITY_HIDDEN) {
            // hide nts thread if needed
            preferences.setHasHiddenNoteToSelf(true)
        } else {
            // create note to self thread if needed (?)
            val address = recipient.address
            val ourThread = storage.getThreadId(address) ?: storage.getOrCreateThreadIdFor(address).also {
                storage.setThreadCreationDate(it, 0)
            }
            threadDatabase.setHasSent(ourThread, true)
            storage.setPinned(ourThread, userProfile.ntsPriority > 0)
            preferences.setHasHiddenNoteToSelf(false)
        }

        // Set or reset the shared library to use latest expiration config
        if (messageTimestamp != null) {
            storage.getThreadId(recipient)?.let { theadId ->
                storage.setExpirationConfiguration(
                    storage.getExpirationConfiguration(theadId)?.takeIf { it.updatedTimestampMs > messageTimestamp } ?:
                    ExpirationConfiguration(theadId, userProfile.ntsExpiry, messageTimestamp)
                )
            }
        }
    }

    private data class UpdateGroupInfo(
        val id: AccountId,
        val name: String?,
        val destroyed: Boolean,
        val deleteBefore: Long?,
        val deleteAttachmentsBefore: Long?
    ) {
        constructor(groupInfoConfig: ReadableGroupInfoConfig) : this(
            id = AccountId(groupInfoConfig.id()),
            name = groupInfoConfig.getName(),
            destroyed = groupInfoConfig.isDestroyed(),
            deleteBefore = groupInfoConfig.getDeleteBefore(),
            deleteAttachmentsBefore = groupInfoConfig.getDeleteAttachmentsBefore()
        )
    }

    private fun updateGroup(groupInfoConfig: UpdateGroupInfo) {
        val threadId = storage.getThreadId(fromSerialized(groupInfoConfig.id.hexString)) ?: return
        val recipient = storage.getRecipientForThread(threadId) ?: return
        recipientDatabase.setProfileName(recipient, groupInfoConfig.name)
        profileManager.setName(context, recipient, groupInfoConfig.name.orEmpty())

        // Also update the name in the user groups config
        configFactory.withMutableUserConfigs { configs ->
            configs.userGroups.getClosedGroup(groupInfoConfig.id.hexString)?.let { group ->
                configs.userGroups.set(group.copy(name = groupInfoConfig.name.orEmpty()))
            }
        }

        if (groupInfoConfig.destroyed) {
            handleDestroyedGroup(threadId = threadId)
        } else {
            groupInfoConfig.deleteBefore?.let { removeBefore ->
                val messages = mmsSmsDatabase.getAllMessageRecordsBefore(threadId, TimeUnit.SECONDS.toMillis(removeBefore))
                val (controlMessages, visibleMessages) = messages.partition { it.isControlMessage }

                // Mark visible messages as deleted, and control messages actually deleted.
                conversationRepository.markAsDeletedLocally(visibleMessages.toSet(), context.getString(R.string.deleteMessageDeletedGlobally))
                conversationRepository.deleteMessages(controlMessages.toSet(), threadId)
            }
            groupInfoConfig.deleteAttachmentsBefore?.let { removeAttachmentsBefore ->
                val messagesWithAttachment = mmsSmsDatabase.getAllMessageRecordsBefore(threadId, TimeUnit.SECONDS.toMillis(removeAttachmentsBefore))
                    .filterTo(mutableSetOf()) { it is MmsMessageRecord && it.containsAttachment }

                conversationRepository.markAsDeletedLocally(messagesWithAttachment,  context.getString(R.string.deleteMessageDeletedGlobally))
            }
        }
    }

    private val MmsMessageRecord.containsAttachment: Boolean
        get() = this.slideDeck.slides.isNotEmpty() && !this.slideDeck.isVoiceNote

    private data class UpdateContacts(val contacts: List<Contact>)

    private fun updateContacts(contacts: UpdateContacts, messageTimestamp: Long?) {
        storage.syncLibSessionContacts(contacts.contacts, messageTimestamp)
    }

    private data class UpdateUserGroupsInfo(
        val communityInfo: List<GroupInfo.CommunityGroupInfo>,
        val legacyGroupInfo: List<GroupInfo.LegacyGroupInfo>,
        val closedGroupInfo: List<GroupInfo.ClosedGroupInfo>
    ) {
        constructor(userGroups: ReadableUserGroupsConfig) : this(
            communityInfo = userGroups.allCommunityInfo(),
            legacyGroupInfo = userGroups.allLegacyGroupInfo(),
            closedGroupInfo = userGroups.allClosedGroupInfo()
        )
    }

    private fun updateUserGroups(userGroups: UpdateUserGroupsInfo, messageTimestamp: Long?) {
        val localUserPublicKey = storage.getUserPublicKey() ?: return Log.w(
            TAG,
            "No user public key when trying to update user groups from config"
        )
        val allOpenGroups = storage.getAllOpenGroups()
        val toDeleteCommunities = allOpenGroups.filter {
            Conversation.Community(BaseCommunityInfo(it.value.server, it.value.room, it.value.publicKey), 0, false).baseCommunityInfo.fullUrl() !in userGroups.communityInfo.map { it.community.fullUrl() }
        }

        val existingCommunities: Map<Long, OpenGroup> = allOpenGroups.filterKeys { it !in toDeleteCommunities.keys }
        val toAddCommunities = userGroups.communityInfo.filter { it.community.fullUrl() !in existingCommunities.map { it.value.joinURL } }
        val existingJoinUrls = existingCommunities.values.map { it.joinURL }

        val existingLegacyClosedGroups = storage.getAllGroups(includeInactive = true).filter { it.isLegacyGroup }
        val lgcIds = userGroups.legacyGroupInfo.map { it.accountId }
        val toDeleteLegacyClosedGroups = existingLegacyClosedGroups.filter { group ->
            GroupUtil.doubleDecodeGroupId(group.encodedId) !in lgcIds
        }

        // delete the ones which are not listed in the config
        toDeleteCommunities.values.forEach { openGroup ->
            OpenGroupManager.delete(openGroup.server, openGroup.room, context)
        }

        toDeleteLegacyClosedGroups.forEach { deleteGroup ->
            val threadId = storage.getThreadId(deleteGroup.encodedId)
            if (threadId != null) {
                ClosedGroupManager.silentlyRemoveGroup(context,threadId,
                    GroupUtil.doubleDecodeGroupId(deleteGroup.encodedId), deleteGroup.encodedId, localUserPublicKey, delete = true)
            }
        }

        toAddCommunities.forEach { toAddCommunity ->
            val joinUrl = toAddCommunity.community.fullUrl()
            if (!storage.hasBackgroundGroupAddJob(joinUrl)) {
                JobQueue.shared.add(BackgroundGroupAddJob(joinUrl))
            }
        }

        for (groupInfo in userGroups.communityInfo) {
            val groupBaseCommunity = groupInfo.community
            if (groupBaseCommunity.fullUrl() in existingJoinUrls) {
                // add it
                val (threadId, _) = existingCommunities.entries.first { (_, v) -> v.joinURL == groupInfo.community.fullUrl() }
                threadDatabase.setPinned(threadId, groupInfo.priority == PRIORITY_PINNED)
            }
        }

        val existingClosedGroupThreads: Map<AccountId, Long> = threadDatabase.readerFor(threadDatabase.conversationList).use { reader ->
            buildMap(reader.count) {
                var current = reader.next
                while (current != null) {
                    if (current.recipient?.isGroupV2Recipient == true) {
                        put(AccountId(current.recipient.address.toString()), current.threadId)
                    }

                    current = reader.next
                }
            }
        }

        val groupThreadsToKeep = hashMapOf<AccountId, Long>()

        for (closedGroup in userGroups.closedGroupInfo) {
            val recipient = Recipient.from(context, fromSerialized(closedGroup.groupAccountId), false)
            storage.setRecipientApprovedMe(recipient, true)
            storage.setRecipientApproved(recipient, !closedGroup.invited)
            profileManager.setName(context, recipient, closedGroup.name)
            val threadId = storage.getOrCreateThreadIdFor(recipient.address)

            // If we don't already have a date and the config has a date, use it
            if (closedGroup.joinedAtSecs > 0L && threadDatabase.getLastUpdated(threadId) <= 0L) {
                threadDatabase.setCreationDate(
                    threadId,
                    TimeUnit.SECONDS.toMillis(closedGroup.joinedAtSecs)
                )
            }

            groupThreadsToKeep[AccountId(closedGroup.groupAccountId)] = threadId

            storage.setPinned(threadId, closedGroup.priority == PRIORITY_PINNED)

            if (closedGroup.destroyed) {
                handleDestroyedGroup(threadId = threadId)
            }
        }

        val toRemove = existingClosedGroupThreads - groupThreadsToKeep.keys
        Log.d(TAG, "Removing ${toRemove.size} closed groups")
        toRemove.forEach { (_, threadId) ->
            storage.deleteConversation(threadId)
        }

        for (group in userGroups.legacyGroupInfo) {
            val groupId = GroupUtil.doubleEncodeGroupID(group.accountId)
            val existingGroup = existingLegacyClosedGroups.firstOrNull { GroupUtil.doubleDecodeGroupId(it.encodedId) == group.accountId }
            val existingThread = existingGroup?.let { storage.getThreadId(existingGroup.encodedId) }
            if (existingGroup != null) {
                if (group.priority == PRIORITY_HIDDEN && existingThread != null) {
                    ClosedGroupManager.silentlyRemoveGroup(context,existingThread,
                        GroupUtil.doubleDecodeGroupId(existingGroup.encodedId), existingGroup.encodedId, localUserPublicKey, delete = true)
                } else if (existingThread == null) {
                    Log.w(TAG, "Existing group had no thread to hide")
                } else {
                    Log.d(TAG, "Setting existing group pinned status to ${group.priority}")
                    threadDatabase.setPinned(existingThread, group.priority == PRIORITY_PINNED)
                }
            } else {
                val members = group.members.keys.map { fromSerialized(it) }
                val admins = group.members.filter { it.value /*admin = true*/ }.keys.map { fromSerialized(it) }
                val title = group.name
                val formationTimestamp = (group.joinedAtSecs * 1000L)
                storage.createGroup(groupId, title, admins + members, null, null, admins, formationTimestamp)
                storage.setProfileSharing(fromSerialized(groupId), true)
                // Add the group to the user's set of public keys to poll for
                storage.addClosedGroupPublicKey(group.accountId)
                // Store the encryption key pair
                val keyPair = ECKeyPair(DjbECPublicKey(group.encPubKey.data), DjbECPrivateKey(group.encSecKey.data))
                storage.addClosedGroupEncryptionKeyPair(keyPair, group.accountId, clock.currentTimeMills())
                // Notify the PN server
                PushRegistryV1.subscribeGroup(group.accountId, publicKey = localUserPublicKey)
                // Notify the user
                val threadID = storage.getOrCreateThreadIdFor(fromSerialized(groupId))
                threadDatabase.setCreationDate(threadID, formationTimestamp)

                // Note: Commenting out this line prevents the timestamp of room creation being added to a new closed group,
                // which in turn allows us to show the `groupNoMessages` control message text.
                //insertOutgoingInfoMessage(context, groupId, SignalServiceGroup.Type.CREATION, title, members.map { it.serialize() }, admins.map { it.serialize() }, threadID, formationTimestamp)

                // Don't create config group here, it's from a config update
                // Start polling
                legacyClosedGroupPollerV2.startPolling(group.accountId)
            }

            if (messageTimestamp != null) {
                storage.getThreadId(fromSerialized(groupId))?.let { theadId ->
                    storage.setExpirationConfiguration(
                        storage.getExpirationConfiguration(theadId)?.takeIf { it.updatedTimestampMs > messageTimestamp }
                            ?: ExpirationConfiguration(theadId, afterSend(group.disappearingTimer), messageTimestamp)
                    )
                }
            }
        }
    }

    private fun handleDestroyedGroup(
        threadId: Long,
    ) {
        storage.clearMessages(threadId)
    }

    private data class UpdateConvoVolatile(val convos: List<Conversation?>)

    private fun updateConvoVolatile(convos: UpdateConvoVolatile) {
        val extracted = convos.convos.filterNotNull()
        for (conversation in extracted) {
            val threadId = when (conversation) {
                is Conversation.OneToOne -> storage.getThreadIdFor(conversation.accountId, null, null, createThread = false)
                is Conversation.LegacyGroup -> storage.getThreadIdFor("", conversation.groupId,null, createThread = false)
                is Conversation.Community -> storage.getThreadIdFor("",null, "${conversation.baseCommunityInfo.baseUrl.removeSuffix("/")}.${conversation.baseCommunityInfo.room}", createThread = false)
                is Conversation.ClosedGroup -> storage.getThreadIdFor(conversation.accountId, null, null, createThread = false) // New groups will be managed bia libsession
            }
            if (threadId != null) {
                if (conversation.lastRead > storage.getLastSeen(threadId)) {
                    storage.markConversationAsRead(threadId, conversation.lastRead, force = true)
                    storage.updateThread(threadId, false)
                }
            }
        }
    }
}

/**
 * Truncate a string to a specified number of bytes
 *
 * This could split multi-byte characters/emojis.
 */
private fun String.truncate(sizeInBytes: Int): String =
    toByteArray().takeIf { it.size > sizeInBytes }?.take(sizeInBytes)?.toByteArray()?.let(::String) ?: this
