package org.session.libsession.messaging.configs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.MutableConfig
import network.loki.messenger.libsession_util.util.ConfigPush
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log

class ConfigSyncHandler(
    private val configFactory: ConfigFactoryProtocol,
    private val storageProtocol: StorageProtocol,
    @Suppress("OPT_IN_USAGE") scope: CoroutineScope = GlobalScope,
) {
    init {
        scope.launch {
            configFactory.configUpdateNotifications.collect { changes ->
                try {
                    when (changes) {
                        is ConfigUpdateNotification.GroupConfigsDeleted -> {}
                        is ConfigUpdateNotification.GroupConfigsUpdated -> {
                            pushGroupConfigsChangesIfNeeded(changes.groupId)
                        }
                        ConfigUpdateNotification.UserConfigs -> pushUserConfigChangesIfNeeded()
                    }
                } catch (e: Exception) {
                    Log.e("ConfigSyncHandler", "Error handling config update", e)
                }
            }
        }
    }

    private suspend fun pushGroupConfigsChangesIfNeeded(groupId: AccountId): Unit = coroutineScope {

    }

    private suspend fun pushUserConfigChangesIfNeeded(): Unit = coroutineScope {
        val userAuth = requireNotNull(storageProtocol.userAuth) {
            "Current user not available"
        }

        data class PushInformation(
            val namespace: Int,
            val configClass: Class<out MutableConfig>,
            val push: ConfigPush,
        )

        // Gather all the user configs that need to be pushed
        val pushes = configFactory.withMutableUserConfigs { configs ->
            configs.allConfigs()
                .filter { it.needsPush() }
                .map { config ->
                    PushInformation(
                        namespace = config.namespace(),
                        configClass = config.javaClass,
                        push = config.push(),
                    )
                }
                .toList()
        }

        Log.d("ConfigSyncHandler", "Pushing ${pushes.size} configs")

        val snode = SnodeAPI.getSingleTargetSnode(userAuth.accountId.hexString).await()

        val pushTasks = pushes.map { info ->
            val calls = buildList {
                this += SnodeAPI.buildAuthenticatedStoreBatchInfo(
                    info.namespace,
                    SnodeMessage(
                        userAuth.accountId.hexString,
                        Base64.encodeBytes(info.push.config),
                        SnodeMessage.CONFIG_TTL,
                        SnodeAPI.nowWithOffset,
                    ),
                    userAuth
                )

                if (info.push.obsoleteHashes.isNotEmpty()) {
                    this += SnodeAPI.buildAuthenticatedDeleteBatchInfo(
                        messageHashes = info.push.obsoleteHashes,
                        auth = userAuth,
                    )
                }
            }

            async {
                val responses = SnodeAPI.getBatchResponse(
                    snode = snode,
                    publicKey = userAuth.accountId.hexString,
                    requests = calls,
                    sequence = true
                )

                val firstError = responses.results.firstOrNull { !it.isSuccessful }
                check(firstError == null) {
                    "Failed to push config change due to error: ${firstError?.body}"
                }

                val hash = responses.results.first().body.get("hash").asText()
                require(hash.isNotEmpty()) {
                    "Missing server hash for pushed config"
                }

                info to hash
            }
        }

        val pushResults = pushTasks.awaitAll().associateBy { it.first.configClass }

        Log.d("ConfigSyncHandler", "Pushed ${pushResults.size} configs")

        configFactory.withMutableUserConfigs { configs ->
            configs.allConfigs()
                .mapNotNull { config -> pushResults[config.javaClass]?.let { Triple(config, it.first, it.second) } }
                .forEach { (config, info, hash) ->
                    config.confirmPushed(info.push.seqNo, hash)
                }
        }
    }
}