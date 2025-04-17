package org.thoughtcrime.securesms.configs

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.util.ConfigPush
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.StoreMessageResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigPushResult
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.MutableGroupConfigs
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.getGroup
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.retryWithUniformInterval
import org.thoughtcrime.securesms.util.NetworkConnectivity
import javax.inject.Inject

private const val TAG = "ConfigUploader"

/**
 * This class is responsible for sending the local config changes to the swarm.
 *
 * Note: This class is listening ONLY to the config system changes. If you change any local database
 * data, this class will not be aware of it. You'll need to update the config system
 * for this class to pick up these changes.
 *
 * @see ConfigToDatabaseSync For syncing the config changes to the local database.
 *
 * It does so by listening for changes in the config factory.
 */
class ConfigUploader @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val storageProtocol: StorageProtocol,
    private val clock: SnodeClock,
    private val networkConnectivity: NetworkConnectivity,
    private val textSecurePreferences: TextSecurePreferences,
) {
    private var job: Job? = null

    /**
     * A flow that only emits when
     * 1. There's internet connection AND,
     * 2. The onion path is available
     *
     * The value pushed doesn't matter as nothing is emitted when the conditions are not met.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun pathBecomesAvailable(): Flow<*> = networkConnectivity.networkAvailable
        .flatMapLatest { hasNetwork ->
            if (hasNetwork) {
                OnionRequestAPI.hasPath.filter { it }
            } else {
                emptyFlow()
            }
        }

    // A flow that emits true when there's a logged in user
    private fun hasLoggedInUser(): Flow<Boolean> = textSecurePreferences.watchLocalNumber()
        .map { it != null }
        .distinctUntilChanged()


    @OptIn(DelicateCoroutinesApi::class, FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        require(job == null) { "Already started" }

        job = GlobalScope.launch {
            supervisorScope {
                // For any of these events, we need to push the user configs:
                // - The onion path has just become available to use
                // - The user configs have been modified
                // Also, these events are only relevant when there's a logged in user
                val job1 = launch {
                    hasLoggedInUser()
                        .flatMapLatest { loggedIn ->
                            if (loggedIn) {
                                merge(
                                    pathBecomesAvailable(),
                                    configFactory.configUpdateNotifications
                                        .filterIsInstance<ConfigUpdateNotification.UserConfigsModified>()
                                        .debounce(1000L)
                                )
                            } else {
                                emptyFlow()
                            }
                        }
                        .collect {
                            try {
                                retryWithUniformInterval {
                                    pushUserConfigChangesIfNeeded()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to push user configs", e)
                            }
                        }
                }

                val job2 = launch {
                    hasLoggedInUser()
                        .flatMapLatest { loggedIn ->
                            if (loggedIn) {
                                merge(
                                    // When the onion request path changes, we need to examine all the groups
                                    // and push the pending configs for them
                                    pathBecomesAvailable().flatMapLatest {
                                        configFactory.withUserConfigs { configs -> configs.userGroups.allClosedGroupInfo() }
                                            .asSequence()
                                            .filter { !it.destroyed && !it.kicked }
                                            .map { AccountId(it.groupAccountId) }
                                            .asFlow()
                                    },

                                    // Or, when a group config is updated, we need to push the changes for that group
                                    configFactory.configUpdateNotifications
                                        .filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
                                        .map { it.groupId }
                                        .debounce(1000L)
                                )
                            } else {
                                emptyFlow()
                            }
                        }
                        .collect { groupId ->
                        try {
                            retryWithUniformInterval {
                                pushGroupConfigsChangesIfNeeded(groupId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to push group configs", e)
                        }
                    }
                }

                job1.join()
                job2.join()
            }
        }
    }

    private suspend fun pushGroupConfigsChangesIfNeeded(groupId: AccountId) {
        // Only admin can push group configs
        val adminKey = configFactory.getGroup(groupId)?.adminKey
        if (adminKey == null) {
            Log.i(TAG, "Skipping group config push without admin key")
            return
        }

        pushGroupConfigsChangesIfNeeded(adminKey.data, groupId) { groupConfigAccess ->
            configFactory.withMutableGroupConfigs(groupId) {
                groupConfigAccess(it)
            }
        }
    }

    /**
     * Gather the group configs data and tries to push to the swarm. Generally, any change to existing
     * configs will be batched and pushed automatically, namely operations done with
     * [ConfigFactoryProtocol.withMutableGroupConfigs], you shouldn't need to call this method.
     * This method is only useful when you are about to create a new group where you want a
     * finer control over how the configs are pushed/persisted.
     *
     * Note: this method doesn't help you with persisting the config changes to the local database,
     * it's merely a helper to push the changes to the swarm.
     *
     */
    suspend fun pushGroupConfigsChangesIfNeeded(
        adminKey: ByteArray,
        groupId: AccountId,
        groupConfigAccess: ((MutableGroupConfigs) -> Unit) -> Unit
    ) = coroutineScope {
        var membersPush: ConfigPush? = null
        var infoPush: ConfigPush? = null
        var keysPush: ByteArray? = null


        // Gather data to push
        groupConfigAccess { configs ->
            if (configs.groupMembers.needsPush()) {
                membersPush = runCatching { configs.groupMembers.push() }
                    .onFailure { Log.w(TAG, "Error generating group members config push", it) }
                    .getOrNull()
            }

            if (configs.groupInfo.needsPush()) {
                infoPush = runCatching { configs.groupInfo.push() }
                    .onFailure { Log.w(TAG, "Error generating group info config push", it) }
                    .getOrNull()
            }

            keysPush = configs.groupKeys.pendingConfig()
        }

        // Nothing to push?
        if (membersPush == null && infoPush == null && keysPush == null) {
            return@coroutineScope
        }

        Log.d(TAG, "Pushing group configs")

        val snode = SnodeAPI.getSingleTargetSnode(groupId.hexString).await()
        val auth = OwnedSwarmAuth.ofClosedGroup(groupId, adminKey)

        // Keys push is different: it doesn't have the delete call so we don't call pushConfig.
        // Keys must be pushed first because the other configs depend on it.
        val keysPushResult = keysPush?.let { push ->
            SnodeAPI.sendBatchRequest(
                snode = snode,
                publicKey = auth.accountId.hexString,
                request = SnodeAPI.buildAuthenticatedStoreBatchInfo(
                    Namespace.GROUP_KEYS(),
                    SnodeMessage(
                        auth.accountId.hexString,
                        Base64.encodeBytes(push),
                        SnodeMessage.CONFIG_TTL,
                        clock.currentTimeMills(),
                    ),
                    auth
                ),
                responseType = StoreMessageResponse::class.java
            ).let(::listOf).toConfigPushResult()
        }

        // Spawn the config pushing concurrently
        val membersConfigHashTask = membersPush?.let { push ->
            async {
                push to pushConfig(
                    auth,
                    snode,
                    push,
                    Namespace.GROUP_MEMBERS()
                )
            }
        }

        val infoConfigHashTask = infoPush?.let { push ->
            async {
                push to pushConfig(auth, snode, push, Namespace.GROUP_INFO())
            }
        }

        // Wait for all other config push to come back
        val memberPushResult = membersConfigHashTask?.await()
        val infoPushResult = infoConfigHashTask?.await()

        // Confirm the push
        groupConfigAccess { configs ->
            memberPushResult?.let { (push, result) -> configs.groupMembers.confirmPushed(push.seqNo, result.hashes.toTypedArray()) }
            infoPushResult?.let { (push, result) -> configs.groupInfo.confirmPushed(push.seqNo, result.hashes.toTypedArray()) }
            keysPushResult?.let { (hashes, timestamp) ->
                val pendingConfig = configs.groupKeys.pendingConfig()
                if (pendingConfig != null) {
                    for (hash in hashes) {
                        configs.groupKeys.loadKey(pendingConfig, hash, timestamp)
                    }
                }
            }
        }

        Log.i(
            TAG,
            "Pushed group configs, " +
                    "info = ${infoPush != null}, " +
                    "members = ${membersPush != null}, " +
                    "keys = ${keysPush != null}"
        )
    }

    private suspend fun pushConfig(
        auth: SwarmAuth,
        snode: Snode,
        push: ConfigPush,
        namespace: Int
    ): ConfigPushResult {
        // Use a coroutineScope to push all messages concurrently, and if one of them fails the whole
        // process will be cancelled. This is the requirement of pushing config: all messages have
        // to be sent successfully for us to consider this process as success
        val responses = coroutineScope {
            val timestamp = clock.currentTimeMills()

            Log.d(TAG, "Pushing ${push.messages.size} config messages")

            push.messages
                .map { message ->
                    async {
                        SnodeAPI.sendBatchRequest(
                            snode = snode,
                            publicKey = auth.accountId.hexString,
                            request = SnodeAPI.buildAuthenticatedStoreBatchInfo(
                                namespace,
                                SnodeMessage(
                                    auth.accountId.hexString,
                                    Base64.encodeBytes(message.data),
                                    SnodeMessage.CONFIG_TTL,
                                    timestamp,
                                ),
                                auth,
                            ),
                            responseType = StoreMessageResponse::class.java
                        )
                    }
                }
                .awaitAll()
        }

        if (push.obsoleteHashes.isNotEmpty()) {
            SnodeAPI.sendBatchRequest(
                snode = snode,
                publicKey = auth.accountId.hexString,
                request = SnodeAPI.buildAuthenticatedDeleteBatchInfo(auth, push.obsoleteHashes)
            )
        }

        return responses.toConfigPushResult()
    }

    private suspend fun pushUserConfigChangesIfNeeded() = coroutineScope {
        val userAuth = requireNotNull(storageProtocol.userAuth) {
            "Current user not available"
        }

        // Gather all the user configs that need to be pushed
        val pushes = configFactory.withMutableUserConfigs { configs ->
            UserConfigType.entries
                .mapNotNull { type ->
                    val config = configs.getConfig(type)
                    if (!config.needsPush()) {
                        return@mapNotNull null
                    }

                    val configPush = runCatching { config.push() }
                        .onFailure { Log.w(TAG, "Error generating $type config", it) }
                        .getOrNull()
                        ?: return@mapNotNull null

                    type to configPush
                }
        }

        if (pushes.isEmpty()) {
            return@coroutineScope
        }

        Log.d(TAG, "Pushing ${pushes.size} user configs")

        val snode = SnodeAPI.getSingleTargetSnode(userAuth.accountId.hexString).await()

        val pushTasks = pushes.map { (configType, configPush) ->
            async {
                Triple(configType, configPush, pushConfig(
                    userAuth,
                    snode,
                    configPush,
                    configType.namespace
                ))
            }
        }

        val pushResults =
            pushTasks.awaitAll().associate { (configType, push, result) -> configType to (push to result) }

        Log.d(TAG, "Pushed ${pushResults.size} user configs")

        configFactory.confirmUserConfigsPushed(
            contacts = pushResults[UserConfigType.CONTACTS],
            userGroups = pushResults[UserConfigType.USER_GROUPS],
            convoInfoVolatile = pushResults[UserConfigType.CONVO_INFO_VOLATILE],
            userProfile = pushResults[UserConfigType.USER_PROFILE]
        )
    }

    private fun List<StoreMessageResponse>.toConfigPushResult(): ConfigPushResult {
        return ConfigPushResult(
            hashes = map { it.hash },
            timestamp = first().timestamp
        )
    }
}