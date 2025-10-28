package org.thoughtcrime.securesms.configs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.avatars.AvatarCacheCleaner
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.allConfigAddresses
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.CommunityDatabase
import org.thoughtcrime.securesms.database.DraftDatabase
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.SessionMetaProtocol
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.log

private const val TAG = "ConfigToDatabaseSync"

/**
 * This class is responsible for syncing config system's data into the database.
 *
 * @see ConfigUploader For upload config system data into swarm automagically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigToDatabaseSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val threadDatabase: ThreadDatabase,
    private val smsDatabase: SmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val draftDatabase: DraftDatabase,
    private val groupDatabase: GroupDatabase,
    private val groupMemberDatabase: GroupMemberDatabase,
    private val communityDatabase: CommunityDatabase,
    private val lokiAPIDatabase: LokiAPIDatabase,
    private val clock: SnodeClock,
    private val preferences: TextSecurePreferences,
    private val conversationRepository: ConversationRepository,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val messageNotifier: MessageNotifier,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    private val avatarCacheCleaner: AvatarCacheCleaner,
    @param:ManagerScope private val scope: CoroutineScope,
) : OnAppStartupComponent {
    init {
        // Sync conversations from config -> database
        scope.launch {
            preferences.watchLocalNumber()
                .map { it != null }
                .flatMapLatest { loggedIn ->
                    if (loggedIn) {
                        combine(
                            conversationRepository.conversationListAddressesFlow,
                            configFactory.userConfigsChanged(EnumSet.of(UserConfigType.CONVO_INFO_VOLATILE))
                                .castAwayType()
                                .onStart { emit(Unit) }
                                .map { _ -> configFactory.withUserConfigs { it.convoInfoVolatile.all() } },
                            ::Pair
                        )
                    } else {
                        emptyFlow()
                    }
                }
                .distinctUntilChanged()
                .collectLatest { (conversations, convoInfo) ->
                    try {
                        ensureConversations(conversations)
                        updateConvoVolatile(convoInfo)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating conversations from config", e)
                    }
                }
        }
    }

    private fun ensureConversations(addresses: Set<Address.Conversable>) {
        val result = threadDatabase.ensureThreads(addresses)

        if (result.deletedThreads.isNotEmpty()) {
            val deletedThreadIDs = result.deletedThreads.values
            smsDatabase.deleteThreads(deletedThreadIDs, false)
            mmsDatabase.deleteThreads(deletedThreadIDs, updateThread = false)
            draftDatabase.clearDrafts(deletedThreadIDs)

            for (threadId in deletedThreadIDs) {
                lokiMessageDatabase.deleteThread(threadId)
                // Whether approved or not, delete the invite
                lokiMessageDatabase.deleteGroupInviteReferrer(threadId)
            }

            // Not sure why this is here but it was from the original code in Storage.
            // If you can find out what it does, please remove it.
            SessionMetaProtocol.clearReceivedMessages()

            // Some type of convo require additional cleanup, we'll go through them here
            for ((address, threadId) in result.deletedThreads) {
                storage.cancelPendingMessageSendJobs(threadId)

                when (address) {
                    is Address.Community -> deleteCommunityData(address, threadId)
                    is Address.LegacyGroup -> deleteLegacyGroupData(address)
                    is Address.Group -> deleteGroupData(address)
                    is Address.Blinded,
                    is Address.CommunityBlindedId,
                    is Address.Standard,
                    is Address.Unknown -> {
                        // No additional cleanup needed for these types
                    }
                }
            }

            // Initiate cleanup in recipient_settings
            pruneRecipientSettingsAndAvatars()
        }

        // If we created threads, we need to update the thread database with the creation date.
        // And possibly having to fill in some other data.
        for ((address, threadId) in result.createdThreads) {
            when (address) {
                is Address.Community -> onCommunityAdded(address, threadId)
                is Address.Group -> onGroupAdded(address, threadId)
                is Address.LegacyGroup -> onLegacyGroupAdded(address, threadId)
                is Address.Blinded,
                is Address.CommunityBlindedId,
                is Address.Standard,
                is Address.Unknown -> {
                    // No additional action needed for these types
                }
            }
        }
    }

    private fun pruneRecipientSettingsAndAvatars() {
        val addressesToKeep: Set<Address> = buildSet {
            addAll(configFactory.allConfigAddresses())
            addAll(mmsSmsDatabase.getAllReferencedAddresses())
        }

        val removed = recipientSettingsDatabase.cleanupRecipientSettings(addressesToKeep)
        Log.d(TAG, "Recipient settings pruned: $removed orphan rows")

        if (removed > 0) {
            avatarCacheCleaner.launchAvatarCleanup()
        }
    }

    private fun deleteGroupData(address: Address.Group) {
        lokiAPIDatabase.clearLastMessageHashes(address.accountId.hexString)
        lokiAPIDatabase.clearReceivedMessageHashValues(address.accountId.hexString)
    }

    private fun onLegacyGroupAdded(
        address: Address.LegacyGroup,
        threadId: Long
    ) {
        val group = configFactory.withUserConfigs { it.userGroups.getLegacyGroupInfo(address.groupPublicKeyHex) }
            ?: return

        val members = group.members.keys.map { fromSerialized(it) }
        val admins = group.members.filter { it.value /*admin = true*/ }.keys.map { fromSerialized(it) }
        val title = group.name
        val formationTimestamp = (group.joinedAtSecs * 1000L)
        storage.createGroup(address.address, title, admins + members, null, null, admins, formationTimestamp)
        // Add the group to the user's set of public keys to poll for
        storage.addClosedGroupPublicKey(group.accountId)
        // Store the encryption key pair
        val keyPair = ECKeyPair(DjbECPublicKey(group.encPubKey.data), DjbECPrivateKey(group.encSecKey.data))
        storage.addClosedGroupEncryptionKeyPair(keyPair, group.accountId, clock.currentTimeMills())
        // Notify the PN server
        PushRegistryV1.subscribeGroup(group.accountId, publicKey = preferences.getLocalNumber()!!)
        threadDatabase.setCreationDate(threadId, formationTimestamp)
    }

    private fun onGroupAdded(
        address: Address.Group,
        threadId: Long
    ) {
        val joined = configFactory.getGroup(address.accountId)?.joinedAtSecs
        if (joined != null && joined > 0L) {
            threadDatabase.setCreationDate(threadId, joined * 1000L)
        }
    }

    private fun onCommunityAdded(address: Address.Community, threadId: Long) {
        // Clear any existing data for this community
        lokiAPIDatabase.removeLastDeletionServerID(room = address.room, server = address.serverUrl)
        lokiAPIDatabase.removeLastMessageServerID(room = address.room, server = address.serverUrl)
        lokiAPIDatabase.removeLastInboxMessageId(address.serverUrl)
        lokiAPIDatabase.removeLastOutboxMessageId(address.serverUrl)

        val community = configFactory.withUserConfigs {
            it.userGroups.allCommunityInfo()
        }.firstOrNull { it.community.baseUrl == address.serverUrl }?.community

        if (community != null) {
            //TODO: This is to save a community public key in the database, but this
            // data is readily available in the config system, remove this once
            // we refactor the OpenGroupManager to use the config system directly.
            lokiAPIDatabase.setOpenGroupPublicKey(address.serverUrl, community.pubKeyHex)
        }
    }

    private fun deleteCommunityData(address: Address.Community, threadId: Long) {
        lokiAPIDatabase.removeLastDeletionServerID(room = address.room, server = address.serverUrl)
        lokiAPIDatabase.removeLastMessageServerID(room = address.room, server = address.serverUrl)
        lokiAPIDatabase.removeLastInboxMessageId(address.serverUrl)
        lokiAPIDatabase.removeLastOutboxMessageId(address.serverUrl)
        groupDatabase.delete(address.address)
        groupMemberDatabase.delete(address)
        communityDatabase.deleteRoomInfo(address)
    }

    private fun deleteLegacyGroupData(address: Address.LegacyGroup) {
        val myAddress = preferences.getLocalNumber()!!

        // Mark the group as inactive
        storage.setActive(address.address, false)
        storage.removeClosedGroupPublicKey(address.groupPublicKeyHex)
        // Remove the key pairs
        storage.removeAllClosedGroupEncryptionKeyPairs(address.groupPublicKeyHex)
        storage.removeMember(address.address, Address.fromSerialized(myAddress))
        // Notify the PN server
        PushRegistryV1.unsubscribeGroup(closedGroupPublicKey = address.groupPublicKeyHex, publicKey = myAddress)
        messageNotifier.updateNotification(context)
    }

    fun syncGroupConfigs(groupId: AccountId) {
        val info = configFactory.withGroupConfigs(groupId) {
            UpdateGroupInfo(it.groupInfo)
        }

        updateGroup(info)
    }

    private data class UpdateGroupInfo(
        val id: AccountId,
        val name: String?,
        val destroyed: Boolean,
        val deleteBefore: Long?,
        val deleteAttachmentsBefore: Long?,
        val profilePic: UserPic?
    ) {
        constructor(groupInfoConfig: ReadableGroupInfoConfig) : this(
            id = AccountId(groupInfoConfig.id()),
            name = groupInfoConfig.getName(),
            destroyed = groupInfoConfig.isDestroyed(),
            deleteBefore = groupInfoConfig.getDeleteBefore(),
            deleteAttachmentsBefore = groupInfoConfig.getDeleteAttachmentsBefore(),
            profilePic = groupInfoConfig.getProfilePic()
        )
    }

    private fun updateGroup(groupInfoConfig: UpdateGroupInfo) {
        val address = fromSerialized(groupInfoConfig.id.hexString)
        val threadId = storage.getThreadId(address) ?: return

        // Also update the name in the user groups config
        configFactory.withMutableUserConfigs { configs ->
            configs.userGroups.getClosedGroup(groupInfoConfig.id.hexString)?.let { group ->
                configs.userGroups.set(group.copy(name = groupInfoConfig.name.orEmpty()))
            }
        }

        if (groupInfoConfig.destroyed) {
            storage.clearMessages(threadID = threadId)
        } else {
            groupInfoConfig.deleteBefore?.let { removeBefore ->
                val messages = mmsSmsDatabase.getAllMessageRecordsBefore(threadId, TimeUnit.SECONDS.toMillis(removeBefore))
                val (controlMessages, visibleMessages) = messages.map { it.first }.partition { it.isControlMessage }

                // Mark visible messages as deleted, and control messages actually deleted.
                conversationRepository.markAsDeletedLocally(visibleMessages.toSet(), context.getString(R.string.deleteMessageDeletedGlobally))
                conversationRepository.deleteMessages(controlMessages.toSet())

                // if the current user is an admin of this group they should also remove the message from the swarm
                // as a safety measure
                val groupAdminAuth = configFactory.getGroup(groupInfoConfig.id)?.adminKey?.data?.let {
                    OwnedSwarmAuth.ofClosedGroup(groupInfoConfig.id, it)
                } ?: return

                // remove messages from swarm SnodeAPI.deleteMessage
                scope.launch(Dispatchers.Default) {
                    val cleanedHashes: List<String> =
                        messages.asSequence().map { it.second }.filter { !it.isNullOrEmpty() }.filterNotNull().toList()
                    if (cleanedHashes.isNotEmpty()) SnodeAPI.deleteMessage(
                        groupInfoConfig.id.hexString,
                        groupAdminAuth,
                        cleanedHashes
                    )
                }
            }
            groupInfoConfig.deleteAttachmentsBefore?.let { removeAttachmentsBefore ->
                val messagesWithAttachment = mmsSmsDatabase.getAllMessageRecordsBefore(threadId, TimeUnit.SECONDS.toMillis(removeAttachmentsBefore))
                    .map{ it.first}.filterTo(mutableSetOf()) { it is MmsMessageRecord && it.containsAttachment }

                conversationRepository.markAsDeletedLocally(messagesWithAttachment,  context.getString(R.string.deleteMessageDeletedGlobally))
            }
        }
    }

    private val MmsMessageRecord.containsAttachment: Boolean
        get() = this.slideDeck.slides.isNotEmpty() && !this.slideDeck.isVoiceNote


    private fun updateConvoVolatile(convos: List<Conversation?>) {
        for (conversation in convos.asSequence().filterNotNull()) {
            val address: Address.Conversable = when (conversation) {
                is Conversation.OneToOne -> Address.Standard(AccountId(conversation.accountId))
                is Conversation.LegacyGroup -> Address.LegacyGroup(conversation.groupId)
                is Conversation.Community -> Address.Community(serverUrl = conversation.baseCommunityInfo.baseUrl, room = conversation.baseCommunityInfo.room)
                is Conversation.ClosedGroup -> Address.Group(AccountId(conversation.accountId)) // New groups will be managed bia libsession
                is Conversation.BlindedOneToOne -> {
                    // Not supported yet
                    continue
                }
            }

            val threadId = threadDatabase.getThreadIdIfExistsFor(address)

            if (threadId != -1L) {
                if (conversation.lastRead > storage.getLastSeen(threadId)) {
                    storage.markConversationAsRead(
                        threadId,
                        conversation.lastRead,
                        force = true
                    )
                    storage.updateThread(threadId, false)
                }
            }
        }
    }
}
