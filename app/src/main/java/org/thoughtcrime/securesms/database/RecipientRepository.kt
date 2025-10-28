package org.thoughtcrime.securesms.database

import androidx.collection.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.ProStatus
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsession.utilities.toBlinded
import org.session.libsession.utilities.toGroupString
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.RecipientSettings
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.groups.GroupMemberComparator
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.Instant
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This repository is responsible for observing and retrieving recipient data from different sources.
 *
 * Not to be confused with [RecipientSettingsDatabase], where it manages the actual database storage of
 * some recipient data. Note that not all recipient data is stored in the database, as we've moved
 * them to the config system. Details in the [RecipientSettingsDatabase].
 *
 * This class will source the correct recipient data from different sources based on their types.
 */
@Singleton
class RecipientRepository @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val groupDatabase: GroupDatabase,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    private val preferences: TextSecurePreferences,
    private val blindedIdMappingRepository: BlindMappingRepository,
    private val communityDatabase: CommunityDatabase,
    @param:ManagerScope private val managerScope: CoroutineScope,
) {
    private val recipientFlowCache = LruCache<Address, WeakReference<SharedFlow<Recipient>>>(512)

    fun observeRecipient(address: Address): Flow<Recipient> {
        val cache = recipientFlowCache[address]?.get()
        if (cache != null) {
            return cache
        }

        // Create a new flow and put it in the cache.
        Log.d(TAG, "Creating new recipient flow for ${address.debugString}")
        val newFlow = createRecipientFlow(address)
        recipientFlowCache.put(address, WeakReference(newFlow))
        return newFlow
    }

    fun observeSelf(): Flow<Recipient> {
        return preferences.watchLocalNumber()
            .flatMapLatest {
                if (it.isNullOrBlank()) {
                    emptyFlow()
                } else {
                    observeRecipient(it.toAddress())
                }
            }
    }

    fun getSelf(): Recipient {
        return getRecipientSync(preferences.getLocalNumber()!!.toAddress())
    }

    // This function creates a flow that emits the recipient information for the given address,
    // the function itself must be fast, not directly access db and lock free, as it is called from a locked context.
    @OptIn(FlowPreview::class)
    private fun createRecipientFlow(address: Address): SharedFlow<Recipient> {
        return flow {
            while (true) {
                val (value, changeSource) = fetchRecipient(
                    address = address,
                    settingsFetcher = {
                        withContext(Dispatchers.Default) { recipientSettingsDatabase.getSettings(it) }
                    },
                    communityFetcher = { withContext(Dispatchers.Default) { communityDatabase.getRoomInfo(it) } }
                )

                emit(value)
                val evt = changeSource.debounce(200).first()
                Log.d(TAG, "Recipient changed for ${address.debugString}, triggering event: $evt")
            }

        }.shareIn(
            managerScope,
            // replay must be cleared at once when no one is subscribed, so that if no one is subscribed,
            // we will always fetch the latest data. The cache is only valid while there is at least one subscriber.
            SharingStarted.WhileSubscribed(replayExpirationMillis = 0L), replay = 1
        )
    }

    private inline fun fetchRecipient(
        address: Address,
        settingsFetcher: (Address) -> RecipientSettings,
        communityFetcher: (Address.Community) -> OpenGroupApi.RoomInfo?,
    ): Pair<Recipient, Flow<*>> {
        val recipientData =
            address.toBlinded()?.let { blindedIdMappingRepository.findMappings(it).firstOrNull()?.second }
                ?.let(this::getDataFromConfig)
                ?: getDataFromConfig(address)

        val changeSource: Flow<*>
        val value: Recipient

        when (recipientData) {
            is RecipientData.Self -> {
                value = createLocalRecipient(address, recipientData)
                changeSource = merge(
                    configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_PROFILE)),
                    TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_CURRENT_USER_PRO }
                )
            }

            is RecipientData.BlindedContact -> {
                value = Recipient(
                    address = address,
                    data = recipientData,
                )

                changeSource = merge(
                    configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.CONTACTS)),
                    TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO }
                )
            }

            is RecipientData.Contact -> {
                value = createContactRecipient(
                    address = address,
                    basic = recipientData,
                    fallbackSettings = settingsFetcher(address)
                )

                changeSource = merge(
                    configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.CONTACTS)),
                    recipientSettingsDatabase.changeNotification.filter { it == address },
                    TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO }
                )
            }

            is RecipientData.PartialGroup -> {
                value = createGroupV2Recipient(
                    address = address,
                    partial = recipientData,
                    settings = settingsFetcher(address),
                    settingsFetcher = settingsFetcher
                )

                val memberAddresses = recipientData.members.mapTo(hashSetOf()) { it.address }

                changeSource = merge(
                    configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS)),
                    configFactory.configUpdateNotifications
                        .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                        .filter { it.groupId.hexString == address.address },
                    recipientSettingsDatabase.changeNotification.filter { it == address || memberAddresses.contains(it) },
                    TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO }
                )
            }

            null -> {
                // Given address is not backed by the config system so we'll get them from
                // local database.
                // If this is a community inbox, we'll load the underlying blinded recipient settings
                // from the db instead, this is because the "community inbox" recipient is never
                // updated from anywhere, it's purely an address to start a conversation. The data
                // like name and avatar were all updated to the blinded recipients.
                val settings = if (address is Address.CommunityBlindedId) {
                    settingsFetcher(address.blindedId)
                } else {
                    settingsFetcher(address)
                }

                when (address) {
                    is Address.LegacyGroup -> {
                        val group: GroupRecord? =
                            groupDatabase.getGroup(address.toGroupString()).orNull()

                        val groupConfig = configFactory.withUserConfigs {
                            it.userGroups.getLegacyGroupInfo(address.groupPublicKeyHex)
                        }

                        val memberAddresses = group?.members?.toSet().orEmpty()

                        changeSource = merge(
                            groupDatabase.updateNotification,
                            recipientSettingsDatabase.changeNotification.filter { it == address || it in memberAddresses },
                            configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS)),
                        )

                        value = group?.let { createLegacyGroupRecipient(address, groupConfig, it, settings, settingsFetcher) }
                            ?: createGenericRecipient(address, settings)
                    }

                    is Address.Community -> {
                        value = configFactory.withUserConfigs {
                            it.userGroups.getCommunityInfo(address.serverUrl, address.room)
                        }?.let { groupConfig ->
                            createCommunityRecipient(
                                address = address,
                                config = groupConfig,
                                roomInfo = communityFetcher(address),
                                settings = settings
                            )
                        } ?: createGenericRecipient(address, settings)

                        changeSource = merge(
                            recipientSettingsDatabase.changeNotification.filter { it == address },
                            communityDatabase.changeNotification.filter { it == address },
                            configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS)),
                        )
                    }

                    is Address.Standard -> {
                        // If we are a standard address, last attempt to find the
                        // recipient inside all closed groups' member list
                        // members:
                        val allGroups = configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
                        value = allGroups
                            .asSequence()
                            .mapNotNull { groupInfo ->
                                configFactory.withGroupConfigs(AccountId(groupInfo.groupAccountId)) {
                                    it.groupMembers.get(address.address)
                                }?.let(RecipientData::GroupMemberInfo)
                            }
                            .firstOrNull()
                            ?.let { groupMember -> fetchGroupMember(groupMember, settingsFetcher) }
                            ?: createGenericRecipient(address, settings)

                        changeSource = merge(
                            configFactory.configUpdateNotifications.filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                                .filter { it.groupId == address.accountId },
                            configFactory.userConfigsChanged(),
                            recipientSettingsDatabase.changeNotification.filter { it == address },
                            TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO }
                        )
                    }

                    else -> {
                        value = createGenericRecipient(address, settings)
                        changeSource = merge(
                            recipientSettingsDatabase.changeNotification.filter { it == address },
                            TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO }
                        )
                    }
                }
            }
        }

        val updatedChangeSource = (value.proStatus as? ProStatus.Pro)
            ?.validUntil
            ?.let { validUntil ->
                val now = Instant.now()
                if (validUntil >= now) {
                    return@let merge(
                        changeSource,
                        flow {
                            delay(Duration.between(now, validUntil))

                            // Emit anything to trigger a recipient update
                            emit("ProStatus validity change")
                        }
                    )
                }

                changeSource
            }
            ?: changeSource

        return value to updatedChangeSource
    }

    /**
     * A cut-down version of the fetchRecipient function that only fetches the recipient
     * for a group member purpose.
     */
    private inline fun fetchGroupMember(
        member: RecipientData.GroupMemberInfo,
        settingsFetcher: (address: Address) -> RecipientSettings,
    ): Recipient {
        return when (val configData = getDataFromConfig(member.address)) {
            is RecipientData.Self -> {
                createLocalRecipient(member.address, configData)
            }

            is RecipientData.Contact -> {
                createContactRecipient(
                    address = member.address,
                    basic = configData,
                    fallbackSettings = settingsFetcher(member.address)
                )
            }

            else -> {
                // If we don't have the right config data, we can still create a generic recipient
                // with the settings fetched from the database.
                createGenericRecipient(
                    address = member.address,
                    settings = settingsFetcher(member.address),
                    groupMemberInfo = member,
                )
            }
        }
    }

    private inline fun fetchLegacyGroupMember(
        address: Address.Standard,
        settingsFetcher: (address: Address) -> RecipientSettings,
    ): Recipient {
        return when (val configData = getDataFromConfig(address)) {
            is RecipientData.Self -> {
                createLocalRecipient(address, configData)
            }

            is RecipientData.Contact -> {
                createContactRecipient(
                    address = address,
                    basic = configData,
                    fallbackSettings = settingsFetcher(address)
                )
            }

            else -> {
                // If we don't have the right config data, we can still create a generic recipient
                // with the settings fetched from the database.
                createGenericRecipient(
                    address = address,
                    settings = settingsFetcher(address),
                )
            }
        }

    }

    suspend fun getRecipient(address: Address): Recipient {
        return observeRecipient(address).first()
    }

    /**
     * Returns a [Recipient] for the given address, or null if not found.
     *
     * Note that this method might be querying database directly so use with caution.
     */
    @DelicateCoroutinesApi
    fun getRecipientSync(address: Address): Recipient {
        return fetchRecipient(
            address = address,
            settingsFetcher = recipientSettingsDatabase::getSettings,
            communityFetcher = communityDatabase::getRoomInfo
        ).first
    }

    /**
     * Try to source the recipient data from the config system based on the address type.
     *
     * Note that some of the data might not be available in the config system so it's your
     * responsibility to fill in the gaps if needed.
     */
    private fun getDataFromConfig(address: Address): RecipientData.ConfigBased? {
        return when (address) {
            is Address.Standard -> {
                // Is this our own address?
                if (address.address.equals(preferences.getLocalNumber(), ignoreCase = true)) {
                    configFactory.withUserConfigs { configs ->
                        RecipientData.Self(
                            name = configs.userProfile.getName().orEmpty(),
                            avatar = configs.userProfile.getPic().toRemoteFile(),
                            expiryMode = configs.userProfile.getNtsExpiry(),
                            priority = configs.userProfile.getNtsPriority(),
                            proStatus = if (preferences.forceCurrentUserAsPro()) {
                                ProStatus.Pro()
                            } else {
                                // TODO: Get pro status from config
                                ProStatus.None
                            },
                            profileUpdatedAt = null
                        )
                    }
                } else {
                    // Is this a contact?
                    configFactory.withUserConfigs { configs ->
                        configs.contacts.get(address.accountId.hexString)
                    }?.let { contact ->
                        RecipientData.Contact(
                            name = contact.name,
                            nickname = contact.nickname.takeIf { it.isNotBlank() },
                            avatar = contact.profilePicture.toRemoteFile(),
                            approved = contact.approved,
                            approvedMe = contact.approvedMe,
                            blocked = contact.blocked,
                            expiryMode = contact.expiryMode,
                            priority = contact.priority,
                            proStatus = if (preferences.forceOtherUsersAsPro()) {
                                ProStatus.Pro()
                            } else {
                                //TODO: Get contact's pro status from config
                                ProStatus.None
                            },
                            profileUpdatedAt = contact.profileUpdatedEpochSeconds.secondsToInstant(),
                        )
                    }
                }
            }


            // Is this a group?
            is Address.Group -> {
                val groupInfo = configFactory.getGroup(address.accountId) ?: return null
                val groupMemberComparator = GroupMemberComparator(AccountId(preferences.getLocalNumber()!!))
                configFactory.withGroupConfigs(address.accountId) { configs ->
                    RecipientData.PartialGroup(
                        avatar = configs.groupInfo.getProfilePic().toRemoteFile(),
                        expiryMode = configs.groupInfo.expiryMode,
                        name = configs.groupInfo.getName() ?: groupInfo.name,
                        proStatus = if (preferences.forceOtherUsersAsPro()) ProStatus.Pro() else {
                            // TODO: Get group's pro status from config?
                            ProStatus.None
                        },
                        description = configs.groupInfo.getDescription(),
                        members = configs.groupMembers.all()
                            .asSequence()
                            .map(RecipientData::GroupMemberInfo)
                            .sortedWith { o1, o2 ->
                                groupMemberComparator.compare(o1.address.accountId, o2.address.accountId)
                            }
                            .toList(),
                        groupInfo = groupInfo,
                    )
                }
            }

            // Is this a blinded contact?
            is Address.Blinded,
            is Address.CommunityBlindedId -> {
                val blinded = address.toBlinded() ?: return null
                val contact = configFactory.withUserConfigs { it.contacts.getBlinded(blinded.blindedId.hexString) } ?: return null

                RecipientData.BlindedContact(
                    displayName = contact.name,
                    avatar = contact.profilePic.toRemoteFile(),
                    priority = contact.priority,
                    proStatus = if (preferences.forceOtherUsersAsPro()) {
                        ProStatus.Pro()
                    } else {
                        //TODO: Get blinded contact's pro status from?
                        ProStatus.None
                    },

                    // This information is not available in the config but we infer that
                    // if you already have this person as blinded contact, you would have been
                    // able to send them a message before.
                    acceptsBlindedCommunityMessageRequests = true,
                    profileUpdatedAt = contact.profileUpdatedEpochSeconds.secondsToInstant()
                )
            }

            // No config data for these addresses.
            is Address.Community, is Address.LegacyGroup, is Address.Unknown -> null
        }
    }

    /**
     * Creates a RecipientV2 instance from the provided Address and RecipientSettings.
     * Note that this method assumes the recipient is not ourselves.
     */
    private fun createGenericRecipient(
        address: Address,
        settings: RecipientSettings,
        // Additional data for group members, if available.
        groupMemberInfo: RecipientData.GroupMemberInfo? = null,
    ): Recipient {
        check(groupMemberInfo == null || address == groupMemberInfo.address) {
            "Address must match the group member info address if provided."
        }

        return Recipient(
            address = address,
            data = RecipientData.Generic(
                displayName = settings.name?.takeIf { it.isNotBlank() } ?: groupMemberInfo?.name.orEmpty(),
                avatar = settings.profilePic?.toRemoteFile() ?: groupMemberInfo?.profilePic?.toRemoteFile(),
                proStatus = if (preferences.forceOtherUsersAsPro()) {
                    ProStatus.Pro()
                } else {
                    settings.proStatus
                },
                acceptsBlindedCommunityMessageRequests = !settings.blocksCommunityMessagesRequests,
            ),
            mutedUntil = settings.muteUntil,
            autoDownloadAttachments = settings.autoDownloadAttachments,
            notifyType = settings.notifyType,
        )
    }

    private inline fun createGroupV2Recipient(
        address: Address,
        partial: RecipientData.PartialGroup,
        settings: RecipientSettings?,
        settingsFetcher: (Address) -> RecipientSettings,
    ): Recipient {
        return Recipient(
            address = address,
            data = RecipientData.Group(
                partial = partial,
                firstMember = partial.members.firstOrNull()?.let { member ->
                    fetchGroupMember(member, settingsFetcher)
                } ?: getSelf(), // Fallback to have self as first member if no members are present
                secondMember = partial.members.getOrNull(1)?.let { member ->
                    fetchGroupMember(member, settingsFetcher)
                },
            ),
            mutedUntil = settings?.muteUntil,
            autoDownloadAttachments = settings?.autoDownloadAttachments,
            notifyType = settings?.notifyType ?: NotifyType.ALL,
        )
    }

    private inline fun createLegacyGroupRecipient(
        address: Address,
        config: GroupInfo.LegacyGroupInfo?,
        group: GroupRecord, // Local db data
        settings: RecipientSettings?, // Local db data
        settingsFetcher: (Address) -> RecipientSettings
    ): Recipient {
        val memberAddresses = group
            .members
            .asSequence()
            .filterIsInstance<Address.Standard>()
            .toMutableList()


        val myAccountId = AccountId(preferences.getLocalNumber()!!)
        val groupMemberComparator = GroupMemberComparator(myAccountId)

        memberAddresses.sortedWith { a1, a2 ->
            groupMemberComparator.compare(a1.accountId, a2.accountId)
        }

        return Recipient(
            address = address,
            data = RecipientData.LegacyGroup(
                name = group.title,
                priority = config?.priority ?: PRIORITY_VISIBLE,
                members = memberAddresses.associate { address ->
                    address.accountId to if (address in group.admins) {
                        GroupMemberRole.ADMIN
                    } else {
                        GroupMemberRole.STANDARD
                    }
                },
                firstMember = memberAddresses.firstOrNull()
                    ?.let { fetchLegacyGroupMember(it, settingsFetcher) }
                    ?: getSelf(),  // Fallback to have self as first member if no members are present
                secondMember = memberAddresses.getOrNull(1)?.let { fetchLegacyGroupMember(it, settingsFetcher) },
                isCurrentUserAdmin = Address.Standard(myAccountId) in group.admins
            ),
            mutedUntil = settings?.muteUntil,
            autoDownloadAttachments = settings?.autoDownloadAttachments,
            notifyType = settings?.notifyType ?: NotifyType.ALL,
        )
    }



    companion object {
        private const val TAG = "RecipientRepository"

        private fun createLocalRecipient(address: Address, basic: RecipientData.Self): Recipient {
            return Recipient(
                address = address,
                data = basic,
                autoDownloadAttachments = true,
            )
        }

        private val ReadableGroupInfoConfig.expiryMode: ExpiryMode
            get() {
                val timer = getExpiryTimer()
                return when {
                    timer > 0 -> ExpiryMode.AfterSend(timer)
                    else -> ExpiryMode.NONE
                }
            }

        private fun createContactRecipient(
            address: Address,
            basic: RecipientData.Contact,
            fallbackSettings: RecipientSettings?, // Local db data
        ): Recipient {
            return Recipient(
                address = address,
                data = basic,
                mutedUntil = fallbackSettings?.muteUntil,
                autoDownloadAttachments = fallbackSettings?.autoDownloadAttachments,
                notifyType = fallbackSettings?.notifyType ?: NotifyType.ALL,
            )
        }

        private fun createCommunityRecipient(
            address: Address.Community,
            config: GroupInfo.CommunityGroupInfo,
            roomInfo: OpenGroupApi.RoomInfo?,
            settings: RecipientSettings?,
        ): Recipient {
            return Recipient(
                address = address,
                data = RecipientData.Community(
                    roomInfo = roomInfo,
                    priority = config.priority,
                    serverUrl = address.serverUrl,
                    room = address.room,
                    serverPubKey = config.community.pubKeyHex,
                ),
                mutedUntil = settings?.muteUntil,
                autoDownloadAttachments = settings?.autoDownloadAttachments,
                notifyType = settings?.notifyType ?: NotifyType.ALL,
            )
        }

        fun empty(address: Address): Recipient {
            return Recipient(
                address = address,
                data = RecipientData.Generic(),
            )
        }
    }
}