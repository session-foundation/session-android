package org.thoughtcrime.securesms.dependencies

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import network.loki.messenger.libsession_util.util.Contact
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.Sodium
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.messages.control.ConfigurationMessage
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupConfigs
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.MutableGroupConfigs
import org.session.libsession.utilities.MutableUserConfigs
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.DatabaseComponent.Companion.get
import org.thoughtcrime.securesms.groups.GroupManager
import java.util.concurrent.ConcurrentHashMap


class ConfigFactory(
    private val context: Context,
    private val configDatabase: ConfigDatabase,
    private val threadDb: ThreadDatabase,
    private val storage: StorageProtocol,
) : ConfigFactoryProtocol {
    companion object {
        // This is a buffer period within which we will process messages which would result in a
        // config change, any message which would normally result in a config change which was sent
        // before `lastConfigMessage.timestamp - configChangeBufferPeriod` will not  actually have
        // it's changes applied (control text will still be added though)
        const val configChangeBufferPeriod: Long = (2 * 60 * 1000)
    }

    init {
        System.loadLibrary("session_util")
    }

    private class UserConfigsImpl(
        userEd25519SecKey: ByteArray,
        private val userAccountId: AccountId,
        private val configDatabase: ConfigDatabase,
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
                userProfile.initFrom(storage)
            }

            if (convoInfoDump == null) {
                convoInfoVolatile.initFrom(storage, threadDb)
            }
        }

        /**
         * Persists the config if it is dirty and returns the list of classes that were persisted
         */
        fun persistIfDirty(): Boolean {
            return sequenceOf(
                contacts to ConfigDatabase.CONTACTS_VARIANT,
                userGroups to ConfigDatabase.USER_GROUPS_VARIANT,
                userProfile to ConfigDatabase.USER_PROFILE_VARIANT,
                convoInfoVolatile to ConfigDatabase.CONVO_INFO_VARIANT
            ).fold(false) { acc, (config, variant) ->
                if (config.needsDump()) {
                    configDatabase.storeConfig(
                        variant = variant,
                        publicKey = userAccountId.hexString,
                        data = config.dump(),
                        timestamp = SnodeAPI.nowWithOffset
                    )
                    true
                } else {
                    acc
                }
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

        fun persistIfDirty(): Boolean {
            if (groupInfo.needsDump() || groupMembers.needsDump() || groupKeys.needsDump()) {
                configDatabase.storeGroupConfigs(
                    publicKey = groupAccountId.hexString,
                    keysConfig = groupKeys.dump(),
                    infoConfig = groupInfo.dump(),
                    memberConfig = groupMembers.dump(),
                    timestamp = SnodeAPI.nowWithOffset
                )
                return true
            }

            return false
        }

        override fun loadKeys(message: ByteArray, hash: String, timestamp: Long): Boolean {
            return groupKeys.loadKey(message, hash, timestamp, groupInfo.pointer, groupMembers.pointer)
        }

        override fun rekeys() {
            groupKeys.rekey(groupInfo.pointer, groupMembers.pointer)
        }
    }

    private val userConfigs = ConcurrentHashMap<AccountId, UserConfigsImpl>()
    private val groupConfigs = ConcurrentHashMap<AccountId, GroupConfigsImpl>()

    private val _configUpdateNotifications = MutableSharedFlow<ConfigUpdateNotification>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val configUpdateNotifications get() = _configUpdateNotifications

    private fun requiresCurrentUserAccountId(): AccountId =
        AccountId(requireNotNull(storage.getUserPublicKey()) {
            "No logged in user"
        })

    private fun requiresCurrentUserED25519SecKey(): ByteArray =
        requireNotNull(storage.getUserED25519KeyPair()?.secretKey?.asBytes) {
            "No logged in user"
        }

    override fun <T> withUserConfigs(cb: (UserConfigs) -> T): T {
        val userAccountId = requiresCurrentUserAccountId()
        val configs = userConfigs.getOrPut(userAccountId) {
            UserConfigsImpl(
                requiresCurrentUserED25519SecKey(),
                userAccountId,
                threadDb = threadDb,
                configDatabase = configDatabase,
                storage = storage
            )
        }

        return synchronized(configs) {
            cb(configs)
        }
    }

    override fun <T> withMutableUserConfigs(cb: (MutableUserConfigs) -> T): T {
        return withUserConfigs { configs ->
            val result = cb(configs as UserConfigsImpl)

            if (configs.persistIfDirty()) {
                _configUpdateNotifications.tryEmit(ConfigUpdateNotification.UserConfigs)
            }

            result
        }
    }

    override fun <T> withGroupConfigs(groupId: AccountId, cb: (GroupConfigs) -> T): T {
        val configs = groupConfigs.getOrPut(groupId) {
            val groupAdminKey = requireNotNull(withUserConfigs {
                it.userGroups.getClosedGroup(groupId.hexString)
            }) {
                "Group not found"
            }.adminKey

            GroupConfigsImpl(
                requiresCurrentUserED25519SecKey(),
                groupId,
                groupAdminKey,
                configDatabase
            )
        }

        return synchronized(configs) {
            cb(configs)
        }
    }

    override fun <T> withMutableGroupConfigs(
        groupId: AccountId,
        cb: (MutableGroupConfigs) -> T
    ): T {
        return withGroupConfigs(groupId) { configs ->
            val result = cb(configs as GroupConfigsImpl)

            if (configs.persistIfDirty()) {
                _configUpdateNotifications.tryEmit(ConfigUpdateNotification.GroupConfigsUpdated(groupId))
            }

            result
        }
    }

    override fun removeGroup(groupId: AccountId) {
        withMutableUserConfigs {
            it.userGroups.eraseClosedGroup(groupId.hexString)
        }

        if (groupConfigs.remove(groupId) != null) {
            _configUpdateNotifications.tryEmit(ConfigUpdateNotification.GroupConfigsDeleted(groupId))
        }

        configDatabase.deleteGroupConfigs(groupId)
    }

    override fun maybeDecryptForUser(
        encoded: ByteArray,
        domain: String,
        closedGroupSessionId: AccountId
    ): ByteArray? {
        return Sodium.decryptForMultipleSimple(
            encoded = encoded,
            ed25519SecretKey = requireNotNull(storage.getUserED25519KeyPair()?.secretKey?.asBytes) {
                "No logged in user"
            },
            domain = domain,
            senderPubKey = Sodium.ed25519PkToCurve25519(closedGroupSessionId.pubKeyBytes)
        )
    }

    override fun conversationInConfig(
        publicKey: String?,
        groupPublicKey: String?,
        openGroupId: String?,
        visibleOnly: Boolean
    ): Boolean {
        val userPublicKey = storage.getUserPublicKey() ?: return false

        if (openGroupId != null) {
            val threadId = GroupManager.getOpenGroupThreadID(openGroupId, context)
            val openGroup =
                get(context).lokiThreadDatabase().getOpenGroupChat(threadId) ?: return false

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
        return (changeTimestampMs >= (lastUpdateTimestampMs - configChangeBufferPeriod))
    }

    override fun getGroupAuth(groupId: AccountId): SwarmAuth? {
        val (adminKey, authData) = withUserConfigs {
            val group = it.userGroups.getClosedGroup(groupId.hexString)
            group?.adminKey to group?.authData
        }

        return if (adminKey != null) {
            OwnedSwarmAuth.ofClosedGroup(groupId, adminKey)
        } else if (authData != null) {
            GroupSubAccountSwarmAuth(groupId, this, authData)
        } else {
            null
        }
    }

    fun clearAll() {
        //TODO: clear all configsr
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
        .asSequence().filter { it.isLegacyClosedGroup && it.isActive && it.members.size > 1 }
        .mapNotNull { group ->
            val groupAddress = Address.fromSerialized(group.encodedId)
            val groupPublicKey = GroupUtil.doubleDecodeGroupID(groupAddress.serialize()).toHexString()
            val recipient = storage.getRecipientSettings(groupAddress) ?: return@mapNotNull null
            val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: return@mapNotNull null
            val threadId = storage.getThreadId(group.encodedId)
            val isPinned = threadId?.let { storage.isPinned(threadId) } ?: false
            val admins = group.admins.associate { it.serialize() to true }
            val members = group.members.filterNot { it.serialize() !in admins.keys }.associate { it.serialize() to false }
            GroupInfo.LegacyGroupInfo(
                accountId = groupPublicKey,
                name = group.title,
                members = admins + members,
                priority = if (isPinned) ConfigBase.PRIORITY_PINNED else ConfigBase.PRIORITY_VISIBLE,
                encPubKey = (encryptionKeyPair.publicKey as DjbECPublicKey).publicKey,  // 'serialize()' inserts an extra byte
                encSecKey = encryptionKeyPair.privateKey.serialize(),
                disappearingTimer = recipient.expireMessages.toLong(),
                joinedAt = (group.formationTimestamp / 1000L)
            )
        }
        .forEach(this::set)
}

private fun MutableConversationVolatileConfig.initFrom(storage: StorageProtocol, threadDb: ThreadDatabase) {
    threadDb.approvedConversationList.use { cursor ->
        val reader = threadDb.readerFor(cursor)
        var current = reader.next
        while (current != null) {
            val recipient = current.recipient
            val contact = when {
                recipient.isCommunityRecipient -> {
                    val openGroup = storage.getOpenGroup(current.threadId) ?: continue
                    val (base, room, pubKey) = BaseCommunityInfo.parseFullUrl(openGroup.joinURL) ?: continue
                    getOrConstructCommunity(base, room, pubKey)
                }
                recipient.isClosedGroupV2Recipient -> {
                    // It's probably safe to assume there will never be a case where new closed groups will ever be there before a dump is created...
                    // but just in case...
                    getOrConstructClosedGroup(recipient.address.serialize())
                }
                recipient.isLegacyClosedGroupRecipient -> {
                    val groupPublicKey = GroupUtil.doubleDecodeGroupId(recipient.address.serialize())
                    getOrConstructLegacyGroup(groupPublicKey)
                }
                recipient.isContactRecipient -> {
                    if (recipient.isLocalNumber) null // this is handled by the user profile NTS data
                    else if (recipient.isOpenGroupInboxRecipient) null // specifically exclude
                    else if (!recipient.address.serialize().startsWith(IdPrefix.STANDARD.value)) null
                    else getOrConstructOneToOne(recipient.address.serialize())
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

private fun MutableUserProfile.initFrom(storage: StorageProtocol) {
    val ownPublicKey = storage.getUserPublicKey() ?: return
    val config = ConfigurationMessage.getCurrent(listOf()) ?: return
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