package org.session.libsession.messaging.sending_receiving.pollers

import android.os.SystemClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.time.Instant
import kotlin.time.Duration.Companion.days

private const val TAG = "Poller"

typealias PollerRequestToken = Channel<Result<Unit>>

class Poller @AssistedInject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val lokiApiDatabase: LokiAPIDatabaseProtocol,
    private val preferences: TextSecurePreferences,
    private val appVisibilityManager: AppVisibilityManager,
    private val networkConnectivity: NetworkConnectivity,
    private val batchMessageReceiveJobFactory: BatchMessageReceiveJob.Factory,
    private val snodeClock: SnodeClock,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    @Assisted scope: CoroutineScope
) {
    private val userPublicKey: String
        get() = storage.getUserPublicKey().orEmpty()

    private val manualRequestTokens: SendChannel<PollerRequestToken>
    val pollState: StateFlow<PollState>

    init {
        val tokenChannel = Channel<PollerRequestToken>()

        manualRequestTokens = tokenChannel
        pollState = flow { setUpPolling(this, tokenChannel) }
            .stateIn(scope, SharingStarted.Eagerly, PollState.Idle)
    }

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): Poller
    }

    enum class PollState {
        Idle,
        Polling,
    }

    // region Settings
    companion object {
        private const val RETRY_INTERVAL_MS: Long      = 2  * 1000
        private const val MAX_RETRY_INTERVAL_MS: Long  = 15 * 1000
        private const val NEXT_RETRY_MULTIPLIER: Float = 1.2f // If we fail to poll we multiply our current retry interval by this (up to the above max) then try again
    }
    // endregion

    /**
     * Request to do a poll from the poller. If it happens to have other requests pending, they
     * will be batched together and processed at once.
     *
     * Note that if there's any error during the poll, this method will throw the same error.
     */
    suspend fun requestPollOnce() {
        val token = Channel<Result<Unit>>()
        manualRequestTokens.send(token)
        token.receive().getOrThrow()
    }

    // region Private API
    private suspend fun setUpPolling(collector: FlowCollector<PollState>, tokenReceiver: ReceiveChannel<PollerRequestToken>) {
        // Migrate to multipart config when needed
        if (!preferences.migratedToMultiPartConfig) {
            val allConfigNamespaces = intArrayOf(Namespace.USER_PROFILE(),
                Namespace.USER_GROUPS(),
                Namespace.CONTACTS(),
                Namespace.CONVO_INFO_VOLATILE(),
                Namespace.GROUP_KEYS(),
                Namespace.GROUP_INFO(),
                Namespace.GROUP_MEMBERS()
            )
            // To migrate to multi part config, we'll need to fetch all the config messages so we
            // get the chance to process those multipart messages again...
            lokiApiDatabase.clearLastMessageHashesByNamespaces(*allConfigNamespaces)
            receivedMessageHashDatabase.removeAllByNamespaces(*allConfigNamespaces)

            preferences.migratedToMultiPartConfig = true
        }

        val pollPool = hashSetOf<Snode>() // pollPool is the list of snodes we can use while rotating snodes from our swarm
        var retryScalingFactor = 1.0f // We increment the retry interval by NEXT_RETRY_MULTIPLIER times this value, which we bump on each failure

        var scheduledNextPoll = 0L
        var hasPolledUserProfileOnce = false

        while (true) {
            val requestTokens = merge(
                combine(
                    appVisibilityManager.isAppVisible.filter { it },
                    networkConnectivity.networkAvailable.filter { it },
                ) { _, _ ->
                    // If the app is visible and we have network, we can poll but need to stick to
                    // the scheduled next poll time
                    val delayMills = scheduledNextPoll - SystemClock.elapsedRealtime()
                    if (delayMills > 0) {
                        Log.d(TAG, "Delaying next poll by $delayMills ms")
                        delay(delayMills)
                    }

                    mutableListOf()
                },

                tokenReceiver.receiveAsFlow().map { mutableListOf(it) }
            ).first()

            // Drain the request tokens channel so we can process all pending requests at once
            generateSequence { tokenReceiver.tryReceive().getOrNull() }
                .mapTo(requestTokens) { it }

            // When we are only just starting to set up the account, we want to poll only the user
            // profile config so the user can see their name/avatar ASAP. Once this is done, we
            // will do a full poll immediately.
            val pollOnlyUserProfileConfig = !hasPolledUserProfileOnce &&
                    configFactory.withUserConfigs { it.userProfile.activeHashes().isEmpty() }

            Log.d(TAG, "Polling...manualTokenSize=${requestTokens.size}, " +
                    "pollOnlyUserProfileConfig=$pollOnlyUserProfileConfig")

            var pollDelay = RETRY_INTERVAL_MS
            collector.emit(PollState.Polling)
            try {
                // check if the polling pool is empty
                if (pollPool.isEmpty()) {
                    // if it is empty, fill it with the snodes from our swarm
                    pollPool.addAll(SnodeAPI.getSwarm(userPublicKey).await())
                }

                // randomly get a snode from the pool
                val currentNode = pollPool.random()

                // remove that snode from the pool
                pollPool.remove(currentNode)

                poll(currentNode, pollOnlyUserProfileConfig)
                retryScalingFactor = 1f

                requestTokens.forEach { it.trySend(Result.success(Unit)) }

                if (pollOnlyUserProfileConfig) {
                    pollDelay = 0L // If we only polled the user profile config, we need to poll again immediately
                }

                hasPolledUserProfileOnce = true
            } catch (e: CancellationException) {
                Log.w(TAG, "Polling cancelled", e)
                requestTokens.forEach { it.trySend(Result.failure(e)) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error while polling:", e)
                pollDelay = minOf(
                    MAX_RETRY_INTERVAL_MS,
                    (RETRY_INTERVAL_MS * (NEXT_RETRY_MULTIPLIER * retryScalingFactor)).toLong()
                )
                retryScalingFactor++
                requestTokens.forEach { it.trySend(Result.failure(e)) }
            } finally {
                collector.emit(PollState.Idle)
            }

            scheduledNextPoll = SystemClock.elapsedRealtime() + pollDelay
        }
    }

    private fun processPersonalMessages(snode: Snode, messages: List<RetrieveMessageResponse.Message>) {
        if (messages.isEmpty()) {
            return
        }

        lokiApiDatabase.setLastMessageHashValue(
            snode = snode,
            publicKey = userPublicKey,
            newValue = messages
                .maxBy { it.timestamp ?: Instant.EPOCH }.hash,
            namespace = Namespace.DEFAULT()
        )

        receivedMessageHashDatabase.removeDuplicates(
            swarmPublicKey = userPublicKey,
            messages = messages,
            messageHashGetter = { it.hash },
            namespace = Namespace.DEFAULT(),
        ).asSequence()
            .map { msg ->
                MessageReceiveParameters(
                    data = msg.data,
                    serverHash = msg.hash,
                )
            }
            .chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER)
            .forEach { chunk ->
                JobQueue.shared.add(batchMessageReceiveJobFactory.create(
                    messages = chunk,
                    fromCommunity = null
                ))
            }
    }

    private fun processConfig(snode: Snode, messages: List<RetrieveMessageResponse.Message>, forConfig: UserConfigType) {
        Log.d(TAG, "Received ${messages.size} messages for $forConfig")
        val namespace = forConfig.namespace
        val processed = if (messages.isNotEmpty()) {
            lokiApiDatabase.setLastMessageHashValue(
                snode = snode,
                publicKey = userPublicKey,
                newValue = messages.maxBy { it.timestamp ?: Instant.EPOCH }.hash,
                namespace = namespace
            )
            receivedMessageHashDatabase.removeDuplicates(
                swarmPublicKey = userPublicKey,
                messages = messages,
                messageHashGetter = { it.hash },
                namespace = namespace,
            ).map { m ->
                ConfigMessage(data = m.data, hash = m.hash, timestamp = m.timestamp!!.toEpochMilli())
            }
        } else emptyList()

        Log.d(TAG, "About to process ${processed.size} messages for $forConfig")

        if (processed.isEmpty()) return

        try {
            configFactory.mergeUserConfigs(
                userConfigType = forConfig,
                messages = processed,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error while merging user configs", e)
        }

        Log.d(TAG, "Completed processing messages for $forConfig")
    }


    private suspend fun poll(snode: Snode, pollOnlyUserProfileConfig: Boolean) = supervisorScope {
        val userAuth = requireNotNull(storage.userAuth)

        // Get messages call wrapped in an async
        val fetchMessageTask = if (!pollOnlyUserProfileConfig) {
            val request = SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                lastHash = lokiApiDatabase.getLastMessageHashValue(
                    snode = snode,
                    publicKey = userAuth.accountId.hexString,
                    namespace = Namespace.DEFAULT()
                ),
                auth = userAuth,
                maxSize = -2)

            this.async {
                runCatching {
                    SnodeAPI.sendBatchRequest(
                        snode = snode,
                        publicKey = userPublicKey,
                        request = request,
                        responseType = RetrieveMessageResponse.serializer()
                    )
                }
            }
        } else {
            null
        }

        // Determine which configs to fetch
        val configTypesToFetch = if (pollOnlyUserProfileConfig) listOf(UserConfigType.USER_PROFILE)
            else UserConfigType.entries.sortedBy { it.processingOrder }

        // Prepare a set to keep track of hashes of config messages we need to extend
        val hashesToExtend = mutableSetOf<String>()

        // Fetch the config messages in parallel, record the type and the result
        val configFetchTasks = configFactory.withUserConfigs { configs ->
            configTypesToFetch
                .map { type ->
                    val config = configs.getConfig(type)
                    hashesToExtend += config.activeHashes()
                    val request = SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        lastHash = lokiApiDatabase.getLastMessageHashValue(
                            snode = snode,
                            publicKey = userAuth.accountId.hexString,
                            namespace = type.namespace
                        ),
                        auth = userAuth,
                        namespace = type.namespace,
                        maxSize = -8
                    )

                    this.async {
                        type to runCatching {
                            SnodeAPI.sendBatchRequest(
                                snode = snode,
                                publicKey = userPublicKey,
                                request = request,
                                responseType = RetrieveMessageResponse.serializer()
                            )
                        }
                    }
                }
        }

        if (hashesToExtend.isNotEmpty()) {
            launch {
                try {
                    SnodeAPI.sendBatchRequest(
                        snode,
                        userPublicKey,
                        SnodeAPI.buildAuthenticatedAlterTtlBatchRequest(
                            messageHashes = hashesToExtend.toList(),
                            auth = userAuth,
                            newExpiry = snodeClock.currentTimeMills() + 14.days.inWholeMilliseconds,
                            extend = true
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error while extending TTL for hashes", e)
                }
            }
        }

        // From here, we will await on the results of pending tasks

        // Always process the configs before the messages
        for (task in configFetchTasks) {
            val (configType, result) = task.await()

            if (result.isFailure) {
                Log.e(TAG, "Error while fetching config for $configType", result.exceptionOrNull())
                continue
            }

            processConfig(snode, result.getOrThrow().messages, configType)
        }

        // Process the messages if we requested them
        if (fetchMessageTask != null) {
            val result = fetchMessageTask.await()
            if (result.isFailure) {
                Log.e(TAG, "Error while fetching messages", result.exceptionOrNull())
            } else {
                processPersonalMessages(snode, result.getOrThrow().messages)
            }
        }
    }

    private val UserConfigType.processingOrder: Int
        get() = when (this) {
            UserConfigType.USER_PROFILE -> 0
            UserConfigType.CONTACTS -> 1
            UserConfigType.CONVO_INFO_VOLATILE -> 2
            UserConfigType.USER_GROUPS -> 3
        }
}
