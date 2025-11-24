package org.thoughtcrime.securesms.database

import androidx.collection.LruCache
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.ReadableGroupInfoConfig
import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsession.utilities.toBlinded
import org.session.libsession.utilities.toGroupString
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.RecipientSettings
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.groups.GroupMemberComparator
import org.thoughtcrime.securesms.pro.db.ProDatabase
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
    private val prefs: TextSecurePreferences,
    private val groupDatabase: GroupDatabase,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    private val blindedIdMappingRepository: BlindMappingRepository,
    private val communityDatabase: CommunityDatabase,
    private val loginStateRepository: LoginStateRepository,
    @param:ManagerScope private val managerScope: CoroutineScope,
    private val proDatabase: ProDatabase,
    private val snodeClock: Lazy<SnodeClock>,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSelf(): Flow<Recipient> {
        return loginStateRepository
            .loggedInState
            .map { it?.accountId }
            .distinctUntilChanged()
            .flatMapLatest { accountId ->
                if (accountId == null) {
                    emptyFlow()
                } else {
                    observeRecipient(accountId.toAddress())
                }
            }
    }

    fun getSelf(): Recipient {
        return getRecipientSync(loginStateRepository.requireLocalAccountId().toAddress())
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
                    communityFetcher = {
                        withContext(Dispatchers.Default) {
                            communityDatabase.getRoomInfo(
                                it
                            )
                        }
                    },
                    needFlow = true,
                )

                emit(value)
                val evt = changeSource!!.debounce(200).first()
                Log.d(TAG, "Recipient changed for ${address.debugString}, triggering event: $evt")
            }

        }.shareIn(
            managerScope,
            // replay must be cleared at once when no one is subscribed, so that if no one is subscribed,
            // we will always fetch the latest data. The cache is only valid while there is at least one subscriber.
            SharingStarted.WhileSubscribed(replayExpirationMillis = 0L), replay = 1
        )
    }

    /**
     * A context object to collect data during the fetchRecipient process. For now we only
     * collect [RecipientSettings.ProData], this is needed because some recipient (like groups) have
     * multiple sub-recipients, and each of them may have their own pro data.
     * We then collect all the pro data and perform a final calculation at the end of [fetchRecipient].
     */
    private class ProDataContext {
        var proDataList: MutableList<RecipientSettings.ProData>? = null

        fun addProData(proData: RecipientSettings.ProData?) {
            if (proData == null) {
                return
            }

            if (proDataList == null) {
                proDataList = mutableListOf()
            }

            proDataList!!.add(proData)
        }
    }

    private inline fun fetchRecipient(
        address: Address,
        settingsFetcher: (Address) -> RecipientSettings,
        communityFetcher: (Address.Community) -> OpenGroupApi.RoomInfo?,
        needFlow: Boolean,
    ): Pair<Recipient, Flow<*>?> {
        val now = snodeClock.get().currentTime()

        val proDataContext = ProDataContext()

        // Fetch data from config first, this may contain partial information for some kind of recipient
        val configData = getDataFromConfig(
            address = address.toBlinded()
                ?.let { blindedIdMappingRepository.findMappings(it).firstOrNull()?.second } ?: address,
            proDataContext = proDataContext
        )

        val changeSources: MutableList<Flow<*>>?
        val value: Recipient

        when (configData) {
            is RecipientData.Self -> {
                value = createLocalRecipient(address, configData)
                changeSources = if (needFlow) {
                    arrayListOf(
                        configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_PROFILE)),
                        TextSecurePreferences.events.filter {
                            it == TextSecurePreferences.SET_FORCE_CURRENT_USER_PRO
                                    || it == TextSecurePreferences.DEBUG_SUBSCRIPTION_STATUS
                        },
                    )
                } else {
                    null
                }
            }

            is RecipientData.BlindedContact -> {
                value = Recipient(address = address, data = configData)

                changeSources = if (needFlow) {
                    arrayListOf(
                        configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.CONTACTS)),
                        TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO },
                        proDatabase.revocationChangeNotification,
                    )
                } else {
                    null
                }
            }

            is RecipientData.Contact -> {
                value = createContactRecipient(
                    address = address,
                    configData = configData,
                    fallbackSettings = settingsFetcher(address)
                )

                changeSources = if (needFlow) {
                    arrayListOf(
                        configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.CONTACTS)),
                        recipientSettingsDatabase.changeNotification.filter { it == address },
                        TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO },
                        proDatabase.revocationChangeNotification,
                    )
                } else {
                    null
                }
            }

            is RecipientData.Group -> {
                value = createGroupV2Recipient(
                    address = address,
                    proDataContext = proDataContext,
                    configData = configData,
                    settings = settingsFetcher(address),
                    settingsFetcher = settingsFetcher,
                )

                val memberAddresses = configData.members.mapTo(hashSetOf()) { it.address }

                changeSources = if (needFlow) {
                    arrayListOf(
                        configFactory.userConfigsChanged(onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS,
                            UserConfigType.USER_PROFILE)),
                        configFactory.configUpdateNotifications
                            .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                            .filter { it.groupId.hexString == address.address },
                        recipientSettingsDatabase.changeNotification.filter {
                            it == address || memberAddresses.contains(
                                it
                            )
                        },
                        TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO },
                        proDatabase.revocationChangeNotification,
                    )
                } else {
                    null
                }
            }

            else -> {
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

                        changeSources = if (needFlow) {
                            arrayListOf(
                                groupDatabase.updateNotification,
                                recipientSettingsDatabase.changeNotification.filter { it == address || it in memberAddresses },
                                configFactory.userConfigsChanged(
                                    onlyConfigTypes = EnumSet.of(
                                        UserConfigType.USER_GROUPS
                                    )
                                ),
                            )
                        } else {
                            null
                        }
                        value = group?.let {
                            createLegacyGroupRecipient(
                                proDataContext = proDataContext,
                                address = address,
                                config = groupConfig,
                                group = it,
                                settings = settings,
                                settingsFetcher = settingsFetcher
                            )
                        } ?: createGenericRecipient(
                            address = address,
                            proDataContext = proDataContext,
                            settings = settings
                        )
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
                        } ?: createGenericRecipient(
                            address = address,
                            proDataContext = proDataContext,
                            settings = settings
                        )

                        changeSources = if (needFlow) {
                            arrayListOf(
                                recipientSettingsDatabase.changeNotification.filter { it == address },
                                communityDatabase.changeNotification.filter { it == address },
                                configFactory.userConfigsChanged(
                                    onlyConfigTypes = EnumSet.of(
                                        UserConfigType.USER_GROUPS
                                    )
                                ),
                            )
                        } else {
                            null
                        }
                    }

                    is Address.Standard -> {
                        // If we are a standard address, last attempt to find the
                        // recipient inside all closed groups' member list
                        // members:
                        val allGroups =
                            configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
                        value = allGroups
                            .asSequence()
                            .mapNotNull { groupInfo ->
                                configFactory.withGroupConfigs(AccountId(groupInfo.groupAccountId)) {
                                    it.groupMembers.get(address.address)
                                }?.let(RecipientData::GroupMemberInfo)
                            }
                            .firstOrNull()
                            ?.let { groupMember ->
                                fetchGroupMember(
                                    proDataContext = proDataContext,
                                    member = groupMember,
                                    settingsFetcher = settingsFetcher
                                )
                            }
                            ?: createGenericRecipient(
                                address = address,
                                proDataContext = proDataContext,
                                settings = settings
                            )

                        changeSources = if (needFlow) {
                            arrayListOf(
                                configFactory.configUpdateNotifications.filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                                    .filter { it.groupId == address.accountId },
                                configFactory.userConfigsChanged(),
                                recipientSettingsDatabase.changeNotification.filter { it == address },
                                TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO },
                                configFactory.userConfigsChanged(EnumSet.of(UserConfigType.USER_PROFILE)),
                            )
                        } else {
                            null
                        }
                    }

                    else -> {
                        value = createGenericRecipient(
                            address = address,
                            proDataContext = proDataContext,
                            settings = settings
                        )

                        changeSources = if (needFlow) {
                            arrayListOf(
                                recipientSettingsDatabase.changeNotification.filter { it == address },
                                TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_OTHER_USERS_PRO },
                                configFactory.userConfigsChanged(EnumSet.of(UserConfigType.USER_PROFILE)),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

        // Calculate the ProData for this recipient
        val proDataList = proDataContext.proDataList
        var proData = if (!proDataList.isNullOrEmpty()) {
            proDataList.removeAll {
                it.isExpired(now) || proDatabase.isRevoked(it.genIndexHash)
            }

            // The pro data that goes into the recipient, should show the one with the most information
            proDataList.firstOrNull { it.showProBadge }?.let {
                RecipientData.ProData(it.showProBadge)
            }
        } else {
            null
        }

        // Debug overrides from preferences
        if (value.isSelf && proData == null && prefs.forceCurrentUserAsPro()) {
            proData = RecipientData.ProData(showProBadge = true)
        } else if (!value.isSelf
            && (value.address is Address.Standard || value.address is Address.Group)
            && proData == null
            && prefs.forceOtherUsersAsPro()) {
            proData = RecipientData.ProData(showProBadge = true)
        }

        val updatedValue = if (value.data.proData != proData && proData != null) {
            value.copy(data = value.data.setProData(proData))
        } else {
            value
        }

        if (changeSources != null && !proDataList.isNullOrEmpty()) {
            // If we have valid pro data, we need to add a flow to trigger a re-fetch when
            // the earliest pro proof expires.
            val earliestProExpiry = proDataList.minOf { it.expiry }

            val delayMills = Duration.between(now, earliestProExpiry).toMillis()
            // Add a flow that triggers when the pro proof expires
            changeSources.add(flowOf("Pro proof expires")
                .onStart { delay(delayMills) })
        }

        return updatedValue to changeSources?.let { merge(*it.toTypedArray()) }
    }

    /**
     * A cut-down version of the fetchRecipient function that only fetches the recipient
     * for a group member purpose.
     */
    private inline fun fetchGroupMember(
        proDataContext: ProDataContext?,
        member: RecipientData.GroupMemberInfo,
        settingsFetcher: (address: Address) -> RecipientSettings
    ): Recipient {
        return when (val configData = getDataFromConfig(member.address, proDataContext)) {
            is RecipientData.Self -> {
                createLocalRecipient(member.address, configData)
            }

            is RecipientData.Contact -> {
                createContactRecipient(
                    address = member.address,
                    configData = configData,
                    fallbackSettings = settingsFetcher(member.address)
                )
            }

            else -> {
                // If we don't have the right config data, we can still create a generic recipient
                // with the settings fetched from the database.
                createGenericRecipient(
                    address = member.address,
                    proDataContext = proDataContext,
                    settings = settingsFetcher(member.address),
                    groupMemberInfo = member
                )
            }
        }
    }

    private inline fun fetchLegacyGroupMember(
        address: Address.Standard,
        proDataContext: ProDataContext?,
        settingsFetcher: (address: Address) -> RecipientSettings,
    ): Recipient {
        return when (val configData = getDataFromConfig(address, proDataContext)) {
            is RecipientData.Self -> {
                createLocalRecipient(address, configData)
            }

            is RecipientData.Contact -> {
                createContactRecipient(
                    address = address,
                    configData = configData,
                    fallbackSettings = settingsFetcher(address)
                )
            }

            else -> {
                // If we don't have the right config data, we can still create a generic recipient
                // with the settings fetched from the database.
                createGenericRecipient(
                    address = address,
                    proDataContext = proDataContext,
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
            communityFetcher = communityDatabase::getRoomInfo,
            needFlow = false,
        ).first
    }

    /**
     * Try to source the recipient data from the config system based on the address type.
     *
     * Note that some of the data might not be available in the config system so it's your
     * responsibility to fill in the gaps if needed.
     */
    private fun getDataFromConfig(
        address: Address,
        proDataContext: ProDataContext?
    ): RecipientData? {
        return when (address) {
            is Address.Standard -> {
                // Is this our own address?
                if (address.address.equals(
                        loginStateRepository.requireLocalNumber(),
                        ignoreCase = true
                    )
                ) {
                    configFactory.withUserConfigs { configs ->
                        val pro = configs.userProfile.getProConfig()

                        if (pro != null) {
                            proDataContext?.addProData(
                                RecipientSettings.ProData(
                                    showProBadge = configs.userProfile.getProFeatures().contains(
                                        ProFeature.PRO_BADGE
                                    ),
                                    expiry = Instant.ofEpochMilli(pro.proProof.expiryMs),
                                    genIndexHash = pro.proProof.genIndexHashHex,
                                )
                            )
                        }

                        RecipientData.Self(
                            name = configs.userProfile.getName().orEmpty(),
                            avatar = configs.userProfile.getPic().toRemoteFile(),
                            expiryMode = configs.userProfile.getNtsExpiry(),
                            priority = configs.userProfile.getNtsPriority(),
                            proData = null, // final ProData will be calculated later
                            profileUpdatedAt = null,
                        )
                    }
                } else {
                    // Is this a contact?

                    configFactory.withUserConfigs { configs ->
                        configs.contacts.get(address.accountId.hexString)?.let {
                            it to configs.convoInfoVolatile.getOneToOne(address.accountId.hexString)
                        }
                    }?.let { (contact, convo) ->
                        if (convo?.proProofInfo != null && proDataContext != null) {
                            proDataContext.addProData(
                                RecipientSettings.ProData(
                                    showProBadge = contact.proFeatures.contains(ProFeature.PRO_BADGE),
                                    expiry = convo.proProofInfo!!.expiry,
                                    genIndexHash = convo.proProofInfo!!.genIndexHash.data.toHexString(),
                                )
                            )
                        }

                        RecipientData.Contact(
                            name = contact.name,
                            nickname = contact.nickname.takeIf { it.isNotBlank() },
                            avatar = contact.profilePicture.toRemoteFile(),
                            approved = contact.approved,
                            approvedMe = contact.approvedMe,
                            blocked = contact.blocked,
                            expiryMode = contact.expiryMode,
                            priority = contact.priority,
                            proData = null, // final ProData will be calculated later
                            profileUpdatedAt = contact.profileUpdatedEpochSeconds.secondsToInstant(),
                        )
                    }
                }
            }


            // Is this a group?
            is Address.Group -> {
                val groupInfo = configFactory.getGroup(address.accountId) ?: return null
                val groupMemberComparator =
                    GroupMemberComparator(loginStateRepository.requireLocalAccountId())

                configFactory.withGroupConfigs(address.accountId) { configs ->
                    RecipientData.Group(
                        avatar = configs.groupInfo.getProfilePic().toRemoteFile(),
                        expiryMode = configs.groupInfo.expiryMode,
                        name = configs.groupInfo.getName() ?: groupInfo.name,
                        proData = null, // final ProData will be calculated later
                        description = configs.groupInfo.getDescription(),
                        members = configs.groupMembers.all()
                            .asSequence()
                            .map(RecipientData::GroupMemberInfo)
                            .sortedWith { o1, o2 ->
                                groupMemberComparator.compare(
                                    o1.address.accountId,
                                    o2.address.accountId
                                )
                            }
                            .toList(),
                        groupInfo = groupInfo,
                        firstMember = null,
                        secondMember = null,
                    )
                }
            }

            // Is this a blinded contact?
            is Address.Blinded,
            is Address.CommunityBlindedId -> {
                val blinded = address.toBlinded() ?: return null
                val (contact, convo) = configFactory.withUserConfigs { configs ->
                    configs.contacts.getBlinded(blinded.blindedId.hexString)?.let {
                        it to configs.convoInfoVolatile.getBlindedOneToOne(blinded.blindedId.hexString)
                    }
                } ?: return null

                if (convo?.proProofInfo != null && proDataContext != null) {
                    proDataContext.addProData(
                        RecipientSettings.ProData(
                            showProBadge = contact.proFeatures.contains(ProFeature.PRO_BADGE),
                            expiry = convo.proProofInfo!!.expiry,
                            genIndexHash = convo.proProofInfo!!.genIndexHash.data.toHexString(),
                        )
                    )
                }

                RecipientData.BlindedContact(
                    displayName = contact.name,
                    avatar = contact.profilePic.toRemoteFile(),
                    priority = contact.priority,
                    proData = null, // final ProData will be calculated later

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
        proDataContext: ProDataContext?,
        settings: RecipientSettings,
        // Additional data for group members, if available.
        groupMemberInfo: RecipientData.GroupMemberInfo? = null,
    ): Recipient {
        check(groupMemberInfo == null || address == groupMemberInfo.address) {
            "Address must match the group member info address if provided."
        }

        if (settings.proData != null && proDataContext != null) {
            proDataContext.addProData(settings.proData)
        }

        return Recipient(
            address = address,
            data = RecipientData.Generic(
                displayName = settings.name?.takeIf { it.isNotBlank() }
                    ?: groupMemberInfo?.name.orEmpty(),
                avatar = settings.profilePic?.toRemoteFile()
                    ?: groupMemberInfo?.profilePic?.toRemoteFile(),
                acceptsBlindedCommunityMessageRequests = !settings.blocksCommunityMessagesRequests,
            ),
            mutedUntil = settings.muteUntil,
            autoDownloadAttachments = settings.autoDownloadAttachments,
            notifyType = settings.notifyType,
        )
    }

    private inline fun createGroupV2Recipient(
        address: Address,
        proDataContext: ProDataContext?,
        configData: RecipientData.Group,
        settings: RecipientSettings?,
        settingsFetcher: (Address) -> RecipientSettings,
    ): Recipient {
        return Recipient(
            address = address,
            data = configData.copy(
                firstMember = configData.members.firstOrNull()?.let { member ->
                    fetchGroupMember(proDataContext?.takeIf { member.isAdmin }, member, settingsFetcher)
                } ?: getSelf(), // Fallback to have self as first member if no members are present
                secondMember = configData.members.getOrNull(1)?.let { member ->
                    fetchGroupMember(proDataContext?.takeIf { member.isAdmin }, member, settingsFetcher)
                },
            ),
            mutedUntil = settings?.muteUntil,
            autoDownloadAttachments = settings?.autoDownloadAttachments,
            notifyType = settings?.notifyType ?: NotifyType.ALL,
        )
    }

    private inline fun createLegacyGroupRecipient(
        proDataContext: ProDataContext?,
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


        val myAccountId = loginStateRepository.requireLocalAccountId()
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
                    ?.let { fetchLegacyGroupMember(it, proDataContext, settingsFetcher) }
                    ?: getSelf(),  // Fallback to have self as first member if no members are present
                secondMember = memberAddresses.getOrNull(1)
                    ?.let { fetchLegacyGroupMember(it, proDataContext, settingsFetcher) },
                isCurrentUserAdmin = Address.Standard(myAccountId) in group.admins
            ),
            mutedUntil = settings?.muteUntil,
            autoDownloadAttachments = settings?.autoDownloadAttachments,
            notifyType = settings?.notifyType ?: NotifyType.ALL,
        )
    }


    companion object {
        private const val TAG = "RecipientRepository"


        private val ReadableGroupInfoConfig.expiryMode: ExpiryMode
            get() {
                val timer = getExpiryTimer()
                return when {
                    timer > 0 -> ExpiryMode.AfterSend(timer)
                    else -> ExpiryMode.NONE
                }
            }

        private fun createLocalRecipient(address: Address, configData: RecipientData.Self): Recipient {
            return Recipient(
                address = address,
                data = configData,
                autoDownloadAttachments = true,
            )
        }

        private fun createContactRecipient(
            address: Address,
            configData: RecipientData.Contact,
            fallbackSettings: RecipientSettings?, // Local db data
        ): Recipient {
            return Recipient(
                address = address,
                data = configData,
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