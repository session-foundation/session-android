package org.thoughtcrime.securesms.configs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_PINNED
import network.loki.messenger.libsession_util.ReadableContacts
import network.loki.messenger.libsession_util.ReadableConversationVolatileConfig
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.ReadableUserProfile
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.Conversation
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
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.SSKEnvironment.ProfileManagerProtocol.Companion.NAME_PADDED_LENGTH
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.PollerFactory
import org.thoughtcrime.securesms.groups.ClosedGroupManager
import org.thoughtcrime.securesms.groups.OpenGroupManager
import javax.inject.Inject

private const val TAG = "ConfigToDatabaseSync"

/**
 * This class is responsible for syncing config system's data into the database.
 *
 * It does so by listening to the [ConfigFactoryProtocol.configUpdateNotifications] and updating the database accordingly.
 *
 * @see ConfigUploader For upload config system data into swarm automagically.
 */
class ConfigToDatabaseSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val threadDatabase: ThreadDatabase,
    private val recipientDatabase: RecipientDatabase,
    private val mmsDatabase: MmsDatabase,
    private val pollerFactory: PollerFactory,
    private val clock: SnodeClock,
) {
    private var job: Job? = null

    fun start() {
        require(job == null) { "Already started" }

        @Suppress("OPT_IN_USAGE")
        job = GlobalScope.launch {
            val groupMutex = hashMapOf<AccountId, Mutex>()
            val userMutex = Mutex()

            configFactory.configUpdateNotifications.collect { notification ->
                when (notification) {
                    is ConfigUpdateNotification.UserConfigs -> {
                        launch {
                            userMutex.withLock {
                                syncUserConfigs()
                            }
                        }
                    }

                    is ConfigUpdateNotification.GroupConfigsUpdated -> {
                        val groupId = notification.groupId
                        val mutex = groupMutex.getOrPut(groupId) { Mutex() }

                        launch {
                            mutex.withLock {
                                syncGroupConfigs(groupId)
                            }
                        }
                    }

                    is ConfigUpdateNotification.GroupConfigsDeleted -> {
                        groupMutex.remove(notification.groupId)
                    }
                }
            }
        }
    }

    private fun syncGroupConfigs(groupId: AccountId) {
        configFactory.withGroupConfigs(groupId) {
            updateGroup(it.groupInfo)
        }
    }

    private fun syncUserConfigs() {
        val messageTimestamp = clock.currentTimeMills()
        configFactory.withUserConfigs { configs ->
            updateUser(configs.userProfile, messageTimestamp)
            updateUserGroups(configs.userGroups, messageTimestamp)
            updateContacts(configs.contacts, messageTimestamp)
            updateConvoVolatile(configs.convoInfoVolatile)
        }
    }

    private fun updateUser(userProfile: ReadableUserProfile, messageTimestamp: Long) {
        val userPublicKey = storage.getUserPublicKey() ?: return
        // would love to get rid of recipient and context from this
        val recipient = Recipient.from(context, fromSerialized(userPublicKey), false)

        // Update profile name
        val name = userProfile.getName() ?: return
        val userPic = userProfile.getPic()
        val profileManager = SSKEnvironment.shared.profileManager

        name.takeUnless { it.isEmpty() }?.truncate(NAME_PADDED_LENGTH)?.let {
            TextSecurePreferences.setProfileName(context, it)
            profileManager.setName(context, recipient, it)
        }

        // Update profile picture
        if (userPic == UserPic.DEFAULT) {
            storage.clearUserPic()
        } else if (userPic.key.isNotEmpty() && userPic.url.isNotEmpty()
            && TextSecurePreferences.getProfilePictureURL(context) != userPic.url
        ) {
            storage.setUserProfilePicture(userPic.url, userPic.key)
        }

        if (userProfile.getNtsPriority() == PRIORITY_HIDDEN) {
            // delete nts thread if needed
            val ourThread = storage.getThreadId(recipient) ?: return
            storage.deleteConversation(ourThread)
        } else {
            // create note to self thread if needed (?)
            val address = recipient.address
            val ourThread = storage.getThreadId(address) ?: storage.getOrCreateThreadIdFor(address).also {
                storage.setThreadDate(it, 0)
            }
            threadDatabase.setHasSent(ourThread, true)
            storage.setPinned(ourThread, userProfile.getNtsPriority() > 0)
        }

        // Set or reset the shared library to use latest expiration config
        storage.getThreadId(recipient)?.let {
            storage.setExpirationConfiguration(
                storage.getExpirationConfiguration(it)?.takeIf { it.updatedTimestampMs > messageTimestamp } ?:
                ExpirationConfiguration(it, userProfile.getNtsExpiry(), messageTimestamp)
            )
        }
    }

    private fun updateGroup(groupInfoConfig: ReadableGroupInfoConfig) {
        val threadId = storage.getThreadId(fromSerialized(groupInfoConfig.id().hexString)) ?: return
        val recipient = storage.getRecipientForThread(threadId) ?: return
        recipientDatabase.setProfileName(recipient, groupInfoConfig.getName())
        groupInfoConfig.getDeleteBefore()?.let { removeBefore ->
            storage.trimThreadBefore(threadId, removeBefore)
        }
        groupInfoConfig.getDeleteAttachmentsBefore()?.let { removeAttachmentsBefore ->
            mmsDatabase.deleteMessagesInThreadBeforeDate(threadId, removeAttachmentsBefore, onlyMedia = true)
        }
    }

    private fun updateContacts(contacts: ReadableContacts, messageTimestamp: Long) {
        val extracted = contacts.all().toList()
        storage.addLibSessionContacts(extracted, messageTimestamp)
    }

    private fun updateUserGroups(userGroups: ReadableUserGroupsConfig, messageTimestamp: Long) {
        val localUserPublicKey = storage.getUserPublicKey() ?: return Log.w(
            "Loki",
            "No user public key when trying to update user groups from config"
        )
        val communities = userGroups.allCommunityInfo()
        val lgc = userGroups.allLegacyGroupInfo()
        val allOpenGroups = storage.getAllOpenGroups()
        val toDeleteCommunities = allOpenGroups.filter {
            Conversation.Community(BaseCommunityInfo(it.value.server, it.value.room, it.value.publicKey), 0, false).baseCommunityInfo.fullUrl() !in communities.map { it.community.fullUrl() }
        }

        val existingCommunities: Map<Long, OpenGroup> = allOpenGroups.filterKeys { it !in toDeleteCommunities.keys }
        val toAddCommunities = communities.filter { it.community.fullUrl() !in existingCommunities.map { it.value.joinURL } }
        val existingJoinUrls = existingCommunities.values.map { it.joinURL }

        val existingLegacyClosedGroups = storage.getAllGroups(includeInactive = true).filter { it.isLegacyClosedGroup }
        val lgcIds = lgc.map { it.accountId }
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

        for (groupInfo in communities) {
            val groupBaseCommunity = groupInfo.community
            if (groupBaseCommunity.fullUrl() in existingJoinUrls) {
                // add it
                val (threadId, _) = existingCommunities.entries.first { (_, v) -> v.joinURL == groupInfo.community.fullUrl() }
                threadDatabase.setPinned(threadId, groupInfo.priority == PRIORITY_PINNED)
            }
        }

        val newClosedGroups = userGroups.allClosedGroupInfo()
        val existingClosedGroups = storage.getAllGroups(includeInactive = true).filter { it.isClosedGroupV2 }
        for (closedGroup in newClosedGroups) {
            val recipient = Recipient.from(context, fromSerialized(closedGroup.groupAccountId.hexString), false)
            storage.setRecipientApprovedMe(recipient, true)
            storage.setRecipientApproved(recipient, !closedGroup.invited)
            val threadId = storage.getOrCreateThreadIdFor(recipient.address)
            storage.setPinned(threadId, closedGroup.priority == PRIORITY_PINNED)
            if (!closedGroup.invited) {
                pollerFactory.pollerFor(closedGroup.groupAccountId)?.start()
            }
        }

        val toRemove = existingClosedGroups.mapTo(hashSetOf()) { it.encodedId } - newClosedGroups.mapTo(hashSetOf()) { it.groupAccountId.hexString }
        Log.d(TAG, "Removing ${toRemove.size} closed groups")
        toRemove.forEach { encodedId ->
            val threadId = storage.getThreadId(encodedId)
            if (threadId != null) {
                storage.removeClosedGroupThread(threadId)
            }

            pollerFactory.pollerFor(AccountId(encodedId))?.stop()
        }

        for (group in lgc) {
            val groupId = GroupUtil.doubleEncodeGroupID(group.accountId)
            val existingGroup = existingLegacyClosedGroups.firstOrNull { GroupUtil.doubleDecodeGroupId(it.encodedId) == group.accountId }
            val existingThread = existingGroup?.let { storage.getThreadId(existingGroup.encodedId) }
            if (existingGroup != null) {
                if (group.priority == PRIORITY_HIDDEN && existingThread != null) {
                    ClosedGroupManager.silentlyRemoveGroup(context,existingThread,
                        GroupUtil.doubleDecodeGroupId(existingGroup.encodedId), existingGroup.encodedId, localUserPublicKey, delete = true)
                } else if (existingThread == null) {
                    Log.w("Loki-DBG", "Existing group had no thread to hide")
                } else {
                    Log.d("Loki-DBG", "Setting existing group pinned status to ${group.priority}")
                    threadDatabase.setPinned(existingThread, group.priority == PRIORITY_PINNED)
                }
            } else {
                val members = group.members.keys.map { fromSerialized(it) }
                val admins = group.members.filter { it.value /*admin = true*/ }.keys.map { fromSerialized(it) }
                val title = group.name
                val formationTimestamp = (group.joinedAt * 1000L)
                storage.createGroup(groupId, title, admins + members, null, null, admins, formationTimestamp)
                storage.setProfileSharing(fromSerialized(groupId), true)
                // Add the group to the user's set of public keys to poll for
                storage.addClosedGroupPublicKey(group.accountId)
                // Store the encryption key pair
                val keyPair = ECKeyPair(DjbECPublicKey(group.encPubKey), DjbECPrivateKey(group.encSecKey))
                storage.addClosedGroupEncryptionKeyPair(keyPair, group.accountId, clock.currentTimeMills())
                // Notify the PN server
                PushRegistryV1.subscribeGroup(group.accountId, publicKey = localUserPublicKey)
                // Notify the user
                val threadID = storage.getOrCreateThreadIdFor(fromSerialized(groupId))
                threadDatabase.setDate(threadID, formationTimestamp)

                // Note: Commenting out this line prevents the timestamp of room creation being added to a new closed group,
                // which in turn allows us to show the `groupNoMessages` control message text.
                //insertOutgoingInfoMessage(context, groupId, SignalServiceGroup.Type.CREATION, title, members.map { it.serialize() }, admins.map { it.serialize() }, threadID, formationTimestamp)

                // Don't create config group here, it's from a config update
                // Start polling
                LegacyClosedGroupPollerV2.shared.startPolling(group.accountId)
            }
            storage.getThreadId(fromSerialized(groupId))?.let {
                storage.setExpirationConfiguration(
                    storage.getExpirationConfiguration(it)?.takeIf { it.updatedTimestampMs > messageTimestamp }
                        ?: ExpirationConfiguration(it, afterSend(group.disappearingTimer), messageTimestamp)
                )
            }
        }
    }

    private fun updateConvoVolatile(convos: ReadableConversationVolatileConfig) {
        val extracted = convos.all().filterNotNull()
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
                }
                storage.updateThread(threadId, false)
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
