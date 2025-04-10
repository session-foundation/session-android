package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.Contacts
import network.loki.messenger.libsession_util.ConversationVolatileConfig
import network.loki.messenger.libsession_util.GroupInfoConfig
import network.loki.messenger.libsession_util.GroupKeysConfig
import network.loki.messenger.libsession_util.GroupMembersConfig
import network.loki.messenger.libsession_util.MutableContacts
import network.loki.messenger.libsession_util.MutableConversationVolatileConfig
import network.loki.messenger.libsession_util.MutableUserGroupsConfig
import network.loki.messenger.libsession_util.MutableUserProfile
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.UserProfile
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.ConfigPush
import network.loki.messenger.libsession_util.util.Contact
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.Sodium
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.messages.control.ConfigurationMessage
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.ConfigPushResult
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupConfigs
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.MutableGroupConfigs
import org.session.libsession.utilities.MutableUserConfigs
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.UserConfigs
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.getGroup
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.configs.ConfigToDatabaseSync
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.ConfigVariant
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.groups.GroupManager
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write


@Singleton
class ConfigFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configDatabase: ConfigDatabase,
    private val threadDb: ThreadDatabase,
    private val lokiThreadDatabase: LokiThreadDatabase,
    private val storage: Lazy<StorageProtocol>,
    private val textSecurePreferences: TextSecurePreferences,
    private val clock: SnodeClock,
    private val configToDatabaseSync: Lazy<ConfigToDatabaseSync>,
    private val usernameUtils: Lazy<UsernameUtils>
) : ConfigFactoryProtocol {
    companion object {
        // This is a buffer period within which we will process messages which would result in a
        // config change, any message which would normally result in a config change which was sent
        // before `lastConfigMessage.timestamp - configChangeBufferPeriod` will not  actually have
        // it's changes applied (control text will still be added though)
        private const val CONFIG_CHANGE_BUFFER_PERIOD: Long = 2 * 60 * 1000L
    }

    init {
        System.loadLibrary("session_util")
    }

    private val userConfigs = HashMap<AccountId, Pair<ReentrantReadWriteLock, UserConfigsImpl>>()
    private val groupConfigs = HashMap<AccountId, Pair<ReentrantReadWriteLock, GroupConfigsImpl>>()

    private val coroutineScope: CoroutineScope = GlobalScope

    private val _configUpdateNotifications = MutableSharedFlow<ConfigUpdateNotification>()
    override val configUpdateNotifications get() = _configUpdateNotifications

    private fun requiresCurrentUserAccountId(): AccountId =
        AccountId(requireNotNull(textSecurePreferences.getLocalNumber()) {
            "No logged in user"
        })

    private fun requiresCurrentUserED25519SecKey(): ByteArray =
        requireNotNull(storage.get().getUserED25519KeyPair()?.secretKey?.asBytes) {
            "No logged in user"
        }

    private fun ensureUserConfigsInitialized(): Pair<ReentrantReadWriteLock, UserConfigsImpl> {
        val userAccountId = requiresCurrentUserAccountId()

        return synchronized(userConfigs) {
            userConfigs.getOrPut(userAccountId) {
                ReentrantReadWriteLock() to UserConfigsImpl(
                    userEd25519SecKey = requiresCurrentUserED25519SecKey(),
                    userAccountId = userAccountId,
                    threadDb = threadDb,
                    configDatabase = configDatabase,
                    storage = storage.get(),
                    textSecurePreferences = textSecurePreferences,
                    usernameUtils = usernameUtils.get()

                )
            }
        }
    }

    private fun ensureGroupConfigsInitialized(groupId: AccountId): Pair<ReentrantReadWriteLock, GroupConfigsImpl> {
        val groupAdminKey = getGroup(groupId)?.adminKey
        return synchronized(groupConfigs) {
            groupConfigs.getOrPut(groupId) {
                ReentrantReadWriteLock() to GroupConfigsImpl(
                    userEd25519SecKey = requiresCurrentUserED25519SecKey(),
                    groupAccountId = groupId,
                    groupAdminKey = groupAdminKey,
                    configDatabase = configDatabase
                )
            }
        }
    }

    override fun <T> withUserConfigs(cb: (UserConfigs) -> T): T {
        val (lock, configs) = ensureUserConfigsInitialized()
        return lock.read {
            cb(configs)
        }
    }

    /**
     * Perform an operation on the user configs, and notify listeners if the configs were changed.
     *
     * @param cb A function that takes a [UserConfigsImpl] and returns a pair of the result of the operation and a boolean indicating if the configs were changed.
     */
    private fun <T> doWithMutableUserConfigs(cb: (UserConfigsImpl) -> Pair<T, List<ConfigUpdateNotification>>): T {
        val (lock, configs) = ensureUserConfigsInitialized()
        val (result, changed) = lock.write {
            cb(configs)
        }

        if (changed.isNotEmpty()) {
            coroutineScope.launch {
                for (notification in changed) {
                    // Config change notifications are important so we must use suspend version of
                    // emit (not tryEmit)
                    _configUpdateNotifications.emit(notification)
                }
            }
        }

        return result
    }

    override fun mergeUserConfigs(
        userConfigType: UserConfigType,
        messages: List<ConfigMessage>
    ) {
        if (messages.isEmpty()) {
            return
        }

        val result = doWithMutableUserConfigs { configs ->
            val config = when (userConfigType) {
                UserConfigType.CONTACTS -> configs.contacts
                UserConfigType.USER_PROFILE -> configs.userProfile
                UserConfigType.CONVO_INFO_VOLATILE -> configs.convoInfoVolatile
                UserConfigType.USER_GROUPS -> configs.userGroups
            }

            // Merge the list of config messages, we'll be told which messages have been merged
            // and we will then find out which message has the max timestamp
            val maxTimestamp = config.merge(messages.map { it.hash to it.data }.toTypedArray())
                .asSequence()
                .mapNotNull { hash -> messages.firstOrNull { it.hash == hash } }
                .maxOfOrNull { it.timestamp }

            maxTimestamp?.let {
                (config.dump() to it) to
                listOf(ConfigUpdateNotification.UserConfigsMerged(userConfigType))
            } ?: (null to emptyList())
        }

        // Dump now regardless so we can save the timestamp to the database
        if (result != null) {
            val (dump, timestamp) = result

            configDatabase.storeConfig(
                variant = userConfigType.configVariant,
                publicKey = requiresCurrentUserAccountId().hexString,
                data = dump,
                timestamp = timestamp
            )

            configToDatabaseSync.get().syncUserConfigs(userConfigType, timestamp)
        }
    }

    override fun <T> withMutableUserConfigs(cb: (MutableUserConfigs) -> T): T {
        return doWithMutableUserConfigs {
            val result = cb(it)

            val changed = if (it.userGroups.dirty() ||
                it.convoInfoVolatile.dirty() ||
                it.userProfile.dirty() ||
                it.contacts.dirty()) {
                listOf(ConfigUpdateNotification.UserConfigsModified)
            } else {
                emptyList()
            }

            result to changed
        }
    }

    override fun <T> withGroupConfigs(groupId: AccountId, cb: (GroupConfigs) -> T): T {
        val (lock, configs) = ensureGroupConfigsInitialized(groupId)

        return lock.read {
            cb(configs)
        }
    }

    override fun createGroupConfigs(groupId: AccountId, adminKey: ByteArray): MutableGroupConfigs {
        return GroupConfigsImpl(
            userEd25519SecKey = requiresCurrentUserED25519SecKey(),
            groupAccountId = groupId,
            groupAdminKey = adminKey,
            configDatabase = configDatabase
        )
    }

    override fun saveGroupConfigs(groupId: AccountId, groupConfigs: MutableGroupConfigs) {
        check(groupConfigs is GroupConfigsImpl) {
            "The group configs must be the same instance as the one created by createGroupConfigs"
        }

        groupConfigs.dumpIfNeeded(clock)

        synchronized(groupConfigs) {
            this.groupConfigs[groupId] = ReentrantReadWriteLock() to groupConfigs
        }
    }

    private fun <T> doWithMutableGroupConfigs(
        groupId: AccountId,
        cb: (GroupConfigsImpl) -> Pair<T, Boolean>): T {
        val (lock, configs) = ensureGroupConfigsInitialized(groupId)
        val (result, changed) = lock.write {
            cb(configs)
        }

        if (changed) {
            coroutineScope.launch {
                // Config change notifications are important so we must use suspend version of
                // emit (not tryEmit)
                _configUpdateNotifications.emit(ConfigUpdateNotification.GroupConfigsUpdated(groupId))
            }
        }

        return result
    }

    override fun <T> withMutableGroupConfigs(
        groupId: AccountId,
        cb: (MutableGroupConfigs) -> T
    ): T {
        return doWithMutableGroupConfigs(groupId = groupId) {
            cb(it) to it.dumpIfNeeded(clock)
        }
    }

    override fun removeContact(accountId: String) {
        withMutableUserConfigs {
            it.contacts.erase(accountId)
        }
    }

    override fun removeGroup(groupId: AccountId) {
        withMutableUserConfigs {
            it.userGroups.eraseClosedGroup(groupId.hexString)
            it.convoInfoVolatile.eraseClosedGroup(groupId.hexString)
        }

        deleteGroupConfigs(groupId)
    }

    override fun deleteGroupConfigs(groupId: AccountId) {
        configDatabase.deleteGroupConfigs(groupId)

        synchronized(groupConfigs) {
            groupConfigs.remove(groupId)
        }
    }

    override fun decryptForUser(
        encoded: ByteArray,
        domain: String,
        closedGroupSessionId: AccountId
    ): ByteArray? {
        return Sodium.decryptForMultipleSimple(
            encoded = encoded,
            ed25519SecretKey = requireNotNull(storage.get().getUserED25519KeyPair()?.secretKey?.asBytes) {
                "No logged in user"
            },
            domain = domain,
            senderPubKey = Sodium.ed25519PkToCurve25519(closedGroupSessionId.pubKeyBytes)
        )
    }

    override fun mergeGroupConfigMessages(
        groupId: AccountId,
        keys: List<ConfigMessage>,
        info: List<ConfigMessage>,
        members: List<ConfigMessage>
    ) {
        val changed = doWithMutableGroupConfigs(groupId) { configs ->
            // Keys must be loaded first as they are used to decrypt the other config messages
            val keysLoaded = keys.fold(false) { acc, msg ->
                configs.groupKeys.loadKey(msg.data, msg.hash, msg.timestamp, configs.groupInfo.pointer, configs.groupMembers.pointer) || acc
            }

            val infoMerged = info.isNotEmpty() &&
                    configs.groupInfo.merge(info.map { it.hash to it.data }.toTypedArray()).isNotEmpty()

            val membersMerged = members.isNotEmpty() &&
                    configs.groupMembers.merge(members.map { it.hash to it.data }.toTypedArray()).isNotEmpty()

            configs.dumpIfNeeded(clock)

            val changed = (keysLoaded || infoMerged || membersMerged)
            changed to changed
        }

        if (changed) {
            configToDatabaseSync.get().syncGroupConfigs(groupId)
        }
    }

    override fun confirmUserConfigsPushed(
        contacts: Pair<ConfigPush, ConfigPushResult>?,
        userProfile: Pair<ConfigPush, ConfigPushResult>?,
        convoInfoVolatile: Pair<ConfigPush, ConfigPushResult>?,
        userGroups: Pair<ConfigPush, ConfigPushResult>?
    ) {
        if (contacts == null && userProfile == null && convoInfoVolatile == null && userGroups == null) {
            return
        }

        // Confirm push for the configs and gather the dumped data to be saved into the db.
        // For this operation, we will no notify the users as there won't be any real change in terms
        // of the displaying data.
        val dump = doWithMutableUserConfigs { configs ->
            sequenceOf(contacts, userProfile, convoInfoVolatile, userGroups)
                .zip(
                    sequenceOf(
                        UserConfigType.CONTACTS to configs.contacts,
                        UserConfigType.USER_PROFILE to configs.userProfile,
                        UserConfigType.CONVO_INFO_VOLATILE to configs.convoInfoVolatile,
                        UserConfigType.USER_GROUPS to configs.userGroups
                    )
                )
                .filter { (push, _) -> push != null }
                .onEach { (push, config) -> config.second.confirmPushed(push!!.first.seqNo, push.second.hash) }
                .map { (push, config) ->
                    Triple(config.first.configVariant, config.second.dump(), push!!.second.timestamp)
                }.toList() to emptyList()
        }

        // We need to persist the data to the database to save timestamp after the push
        val userAccountId = requiresCurrentUserAccountId()
        for ((variant, data, timestamp) in dump) {
            configDatabase.storeConfig(variant, userAccountId.hexString, data, timestamp)
        }
    }

    override fun confirmGroupConfigsPushed(
        groupId: AccountId,
        members: Pair<ConfigPush, ConfigPushResult>?,
        info: Pair<ConfigPush, ConfigPushResult>?,
        keysPush: ConfigPushResult?
    ) {
        if (members == null && info == null && keysPush == null) {
            return
        }

        doWithMutableGroupConfigs(groupId) { configs ->
            members?.let { (push, result) -> configs.groupMembers.confirmPushed(push.seqNo, result.hash) }
            info?.let { (push, result) -> configs.groupInfo.confirmPushed(push.seqNo, result.hash) }
            keysPush?.let { (hash, timestamp) ->
                val pendingConfig = configs.groupKeys.pendingConfig()
                if (pendingConfig != null) {
                    configs.groupKeys.loadKey(pendingConfig, hash, timestamp, configs.groupInfo.pointer, configs.groupMembers.pointer)
                }
            }

            configs.dumpIfNeeded(clock)

            Unit to true
        }
    }

    override fun conversationInConfig(
        publicKey: String?,
        groupPublicKey: String?,
        openGroupId: String?,
        visibleOnly: Boolean
    ): Boolean {
        val userPublicKey = storage.get().getUserPublicKey() ?: return false

        if (openGroupId != null) {
            val threadId = GroupManager.getOpenGroupThreadID(openGroupId, context)
            val openGroup = lokiThreadDatabase.getOpenGroupChat(threadId) ?: return false

            // Not handling the `hidden` behaviour for communities so just indicate the existence
            return withUserConfigs {
                it.userGroups.getCommunityInfo(openGroup.server, openGroup.room) != null
            }
        } else if (groupPublicKey != null) {
            // Not handling the `hidden` behaviour for legacy groups so just indicate the existence
            return withUserConfigs {
                if (groupPublicKey.startsWith(IdPrefix.GROUP.value)) {
                    it.userGroups.getClosedGroup(groupPublicKey) != null
                } else {
                    it.userGroups.getLegacyGroupInfo(groupPublicKey) != null
                }
            }
        } else if (publicKey == userPublicKey) {
            return withUserConfigs {
                !visibleOnly || it.userProfile.getNtsPriority() != ConfigBase.PRIORITY_HIDDEN
            }
        } else if (publicKey != null) {
            return withUserConfigs {
                (!visibleOnly || it.contacts.get(publicKey)?.priority != ConfigBase.PRIORITY_HIDDEN)
            }
        } else {
            return false
        }
    }

    override fun canPerformChange(
        variant: String,
        publicKey: String,
        changeTimestampMs: Long
    ): Boolean {
        val lastUpdateTimestampMs =
            configDatabase.retrieveConfigLastUpdateTimestamp(variant, publicKey)

        // Ensure the change occurred after the last config message was handled (minus the buffer period)
        return (changeTimestampMs >= (lastUpdateTimestampMs - CONFIG_CHANGE_BUFFER_PERIOD))
    }

    override fun getConfigTimestamp(userConfigType: UserConfigType, publicKey: String): Long {
        return configDatabase.retrieveConfigLastUpdateTimestamp(userConfigType.configVariant, publicKey)
    }

    override fun getGroupAuth(groupId: AccountId): SwarmAuth? {
        val group = getGroup(groupId) ?: return null

        return if (group.adminKey != null) {
            OwnedSwarmAuth.ofClosedGroup(groupId, group.adminKey!!)
        } else if (group.authData != null) {
            GroupSubAccountSwarmAuth(groupId, this, group.authData!!)
        } else {
            null
        }
    }

    fun clearAll() {
        synchronized(userConfigs) {
            userConfigs.clear()
        }

        synchronized(groupConfigs) {
            groupConfigs.clear()
        }
    }

    private class GroupSubAccountSwarmAuth(
        override val accountId: AccountId,
        val factory: ConfigFactory,
        val authData: ByteArray,
    ) : SwarmAuth {
        override val ed25519PublicKeyHex: String?
            get() = null

        override fun sign(data: ByteArray): Map<String, String> {
            return factory.withGroupConfigs(accountId) {
                val auth = it.groupKeys.subAccountSign(data, authData)
                buildMap {
                    put("subaccount", auth.subAccount)
                    put("subaccount_sig", auth.subAccountSig)
                    put("signature", auth.signature)
                }
            }
        }

        override fun signForPushRegistry(data: ByteArray): Map<String, String> {
            return factory.withGroupConfigs(accountId) {
                val auth = it.groupKeys.subAccountSign(data, authData)
                buildMap {
                    put("subkey_tag", auth.subAccount)
                    put("signature", auth.signature)
                }
            }
        }
    }
}

private val UserConfigType.configVariant: ConfigVariant
    get() = when (this) {
        UserConfigType.CONTACTS -> ConfigDatabase.CONTACTS_VARIANT
        UserConfigType.USER_PROFILE -> ConfigDatabase.USER_PROFILE_VARIANT
        UserConfigType.CONVO_INFO_VOLATILE -> ConfigDatabase.CONVO_INFO_VARIANT
        UserConfigType.USER_GROUPS -> ConfigDatabase.USER_GROUPS_VARIANT
    }

/**
 * Sync group data from our local database
 */
private fun MutableUserGroupsConfig.initFrom(storage: StorageProtocol) {
    storage
        .getAllOpenGroups()
        .values
        .asSequence()
        .mapNotNull { openGroup ->
            val (baseUrl, room, pubKey) = BaseCommunityInfo.parseFullUrl(openGroup.joinURL) ?: return@mapNotNull null
            val pubKeyHex = Hex.toStringCondensed(pubKey)
            val baseInfo = BaseCommunityInfo(baseUrl, room, pubKeyHex)
            val threadId = storage.getThreadId(openGroup) ?: return@mapNotNull null
            val isPinned = storage.isPinned(threadId)
            GroupInfo.CommunityGroupInfo(baseInfo, if (isPinned) 1 else 0)
        }
        .forEach(this::set)

    storage
        .getAllGroups(includeInactive = false)
        .asSequence().filter { it.isLegacyGroup && it.isActive && it.members.size > 1 }
        .mapNotNull { group ->
            val groupAddress = Address.fromSerialized(group.encodedId)
            val groupPublicKey = GroupUtil.doubleDecodeGroupID(groupAddress.toString()).toHexString()
            val recipient = storage.getRecipientSettings(groupAddress) ?: return@mapNotNull null
            val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: return@mapNotNull null
            val threadId = storage.getThreadId(group.encodedId)
            val isPinned = threadId?.let { storage.isPinned(threadId) } ?: false
            val admins = group.admins.associate { it.toString() to true }
            val members = group.members.filterNot { it.toString() !in admins.keys }.associate { it.toString() to false }
            GroupInfo.LegacyGroupInfo(
                accountId = groupPublicKey,
                name = group.title,
                members = admins + members,
                priority = if (isPinned) ConfigBase.PRIORITY_PINNED else ConfigBase.PRIORITY_VISIBLE,
                encPubKey = (encryptionKeyPair.publicKey as DjbECPublicKey).publicKey,  // 'serialize()' inserts an extra byte
                encSecKey = encryptionKeyPair.privateKey.serialize(),
                disappearingTimer = recipient.expireMessages.toLong(),
                joinedAtSecs = (group.formationTimestamp / 1000L)
            )
        }
        .forEach(this::set)
}

private fun MutableConversationVolatileConfig.initFrom(storage: StorageProtocol, threadDb: ThreadDatabase) {
    threadDb.approvedConversationList.use { cursor ->
        val reader = threadDb.readerFor(cursor, false)
        var current = reader.next
        while (current != null) {
            val recipient = current.recipient
            val contact = when {
                recipient.isCommunityRecipient -> {
                    val openGroup = storage.getOpenGroup(current.threadId) ?: continue
                    val (base, room, pubKey) = BaseCommunityInfo.parseFullUrl(openGroup.joinURL) ?: continue
                    getOrConstructCommunity(base, room, pubKey)
                }
                recipient.isGroupV2Recipient -> {
                    // It's probably safe to assume there will never be a case where new closed groups will ever be there before a dump is created...
                    // but just in case...
                    getOrConstructClosedGroup(recipient.address.toString())
                }
                recipient.isLegacyGroupRecipient -> {
                    val groupPublicKey = GroupUtil.doubleDecodeGroupId(recipient.address.toString())
                    getOrConstructLegacyGroup(groupPublicKey)
                }
                recipient.isContactRecipient -> {
                    if (recipient.isLocalNumber) null // this is handled by the user profile NTS data
                    else if (recipient.isCommunityInboxRecipient) null // specifically exclude
                    else if (!recipient.address.toString().startsWith(IdPrefix.STANDARD.value)) null
                    else getOrConstructOneToOne(recipient.address.toString())
                }
                else -> null
            }
            if (contact == null) {
                current = reader.next
                continue
            }
            contact.lastRead = current.lastSeen
            contact.unread = false
            set(contact)
            current = reader.next
        }
    }
}

private fun MutableUserProfile.initFrom(storage: StorageProtocol,
                                        usernameUtils: UsernameUtils,
                                        textSecurePreferences: TextSecurePreferences
) {
    val ownPublicKey = storage.getUserPublicKey() ?: return
    val displayName = usernameUtils.getCurrentUsername() ?: return
    val profilePicture = textSecurePreferences.getProfilePictureURL()
    val config = ConfigurationMessage.getCurrent(displayName, profilePicture, listOf()) ?: return
    setName(config.displayName)
    val picUrl = config.profilePicture
    val picKey = config.profileKey
    if (!picUrl.isNullOrEmpty() && picKey.isNotEmpty()) {
        setPic(UserPic(picUrl, picKey))
    }
    val ownThreadId = storage.getThreadId(Address.fromSerialized(ownPublicKey))
    setNtsPriority(
        if (ownThreadId != null)
            if (storage.isPinned(ownThreadId)) ConfigBase.PRIORITY_PINNED else ConfigBase.PRIORITY_VISIBLE
        else ConfigBase.PRIORITY_HIDDEN
    )
}

private fun MutableContacts.initFrom(storage: StorageProtocol) {
    val localUserKey = storage.getUserPublicKey() ?: return
    val contactsWithSettings = storage.getAllContacts().filter { recipient ->
        recipient.accountID != localUserKey && recipient.accountID.startsWith(IdPrefix.STANDARD.value)
                && storage.getThreadId(recipient.accountID) != null
    }.map { contact ->
        val address = Address.fromSerialized(contact.accountID)
        val thread = storage.getThreadId(address)
        val isPinned = if (thread != null) {
            storage.isPinned(thread)
        } else false

        Triple(contact, storage.getRecipientSettings(address)!!, isPinned)
    }
    for ((contact, settings, isPinned) in contactsWithSettings) {
        val url = contact.profilePictureURL
        val key = contact.profilePictureEncryptionKey
        val userPic = if (url.isNullOrEmpty() || key?.isNotEmpty() != true) {
            null
        } else {
            UserPic(url, key)
        }

        val contactInfo = Contact(
            id = contact.accountID,
            name = contact.name.orEmpty(),
            nickname = contact.nickname.orEmpty(),
            blocked = settings.isBlocked,
            approved = settings.isApproved,
            approvedMe = settings.hasApprovedMe(),
            profilePicture = userPic ?: UserPic.DEFAULT,
            priority = if (isPinned) 1 else 0,
            expiryMode = if (settings.expireMessages == 0) ExpiryMode.NONE else ExpiryMode.AfterRead(settings.expireMessages.toLong())
        )
        set(contactInfo)
    }
}

private class UserConfigsImpl(
    userEd25519SecKey: ByteArray,
    private val userAccountId: AccountId,
    private val configDatabase: ConfigDatabase,
    private val textSecurePreferences: TextSecurePreferences,
    private val usernameUtils: UsernameUtils,
    storage: StorageProtocol,
    threadDb: ThreadDatabase,
    contactsDump: ByteArray? = configDatabase.retrieveConfigAndHashes(
        ConfigDatabase.CONTACTS_VARIANT,
        userAccountId.hexString
    ),
    userGroupsDump: ByteArray? = configDatabase.retrieveConfigAndHashes(
        ConfigDatabase.USER_GROUPS_VARIANT,
        userAccountId.hexString
    ),
    userProfileDump: ByteArray? = configDatabase.retrieveConfigAndHashes(
        ConfigDatabase.USER_PROFILE_VARIANT,
        userAccountId.hexString
    ),
    convoInfoDump: ByteArray? = configDatabase.retrieveConfigAndHashes(
        ConfigDatabase.CONVO_INFO_VARIANT,
        userAccountId.hexString
    )
) : MutableUserConfigs {
    override val contacts = Contacts(
        ed25519SecretKey = userEd25519SecKey,
        initialDump = contactsDump,
    )

    override val userGroups = UserGroupsConfig(
        ed25519SecretKey = userEd25519SecKey,
        initialDump = userGroupsDump
    )
    override val userProfile = UserProfile(
        ed25519SecretKey = userEd25519SecKey,
        initialDump = userProfileDump
    )
    override val convoInfoVolatile = ConversationVolatileConfig(
        ed25519SecretKey = userEd25519SecKey,
        initialDump = convoInfoDump,
    )

    init {
        if (contactsDump == null) {
            contacts.initFrom(storage)
        }

        if (userGroupsDump == null) {
            userGroups.initFrom(storage)
        }

        if (userProfileDump == null) {
            userProfile.initFrom(storage, usernameUtils, textSecurePreferences)
        }

        if (convoInfoDump == null) {
            convoInfoVolatile.initFrom(storage, threadDb)
        }
    }
}

private class GroupConfigsImpl(
    userEd25519SecKey: ByteArray,
    private val groupAccountId: AccountId,
    groupAdminKey: ByteArray?,
    private val configDatabase: ConfigDatabase
) : MutableGroupConfigs {
    override val groupInfo = GroupInfoConfig(
        groupPubKey = groupAccountId.pubKeyBytes,
        groupAdminKey = groupAdminKey,
        initialDump = configDatabase.retrieveConfigAndHashes(
            ConfigDatabase.INFO_VARIANT,
            groupAccountId.hexString
        )
    )
    override val groupMembers = GroupMembersConfig(
        groupPubKey = groupAccountId.pubKeyBytes,
        groupAdminKey = groupAdminKey,
        initialDump = configDatabase.retrieveConfigAndHashes(
            ConfigDatabase.MEMBER_VARIANT,
            groupAccountId.hexString
        )
    )
    override val groupKeys = GroupKeysConfig(
        userSecretKey = userEd25519SecKey,
        groupPublicKey = groupAccountId.pubKeyBytes,
        groupAdminKey = groupAdminKey,
        initialDump = configDatabase.retrieveConfigAndHashes(
            ConfigDatabase.KEYS_VARIANT,
            groupAccountId.hexString
        ),
        info = groupInfo,
        members = groupMembers
    )

    fun dumpIfNeeded(clock: SnodeClock): Boolean {
        if (groupInfo.needsDump() || groupMembers.needsDump() || groupKeys.needsDump()) {
            configDatabase.storeGroupConfigs(
                publicKey = groupAccountId.hexString,
                keysConfig = groupKeys.dump(),
                infoConfig = groupInfo.dump(),
                memberConfig = groupMembers.dump(),
                timestamp = clock.currentTimeMills()
            )
            return true
        }

        return false
    }

    override fun rekey() {
        groupKeys.rekey(groupInfo.pointer, groupMembers.pointer)
    }
}