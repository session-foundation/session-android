package org.session.libsession.messaging.sending_receiving.pollers

import android.os.SystemClock
import android.util.SparseArray
import androidx.core.util.valueIterator
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeAPI.KEY_BODY
import org.session.libsession.snode.SnodeAPI.KEY_CODE
import org.session.libsession.snode.SnodeAPI.KEY_RESULTS
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.NetworkConnectivity
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
            lokiApiDatabase.clearReceivedMessageHashValuesByNamespaces(*allConfigNamespaces)

            preferences.migratedToMultiPartConfig = true
        }

        val pollPool = hashSetOf<Snode>() // pollPool is the list of snodes we can use while rotating snodes from our swarm
        var retryScalingFactor = 1.0f // We increment the retry interval by NEXT_RETRY_MULTIPLIER times this value, which we bump on each failure

        var scheduledNextPoll = 0L

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

            Log.d(TAG, "Polling...manualTokenSize?=${requestTokens.size},")

            var pollDelay = RETRY_INTERVAL_MS
            collector.emit(PollState.Polling)
            try {
                // check if the polling pool is empty
                if (pollPool.isEmpty()){
                    // if it is empty, fill it with the snodes from our swarm
                    pollPool.addAll(SnodeAPI.getSwarm(userPublicKey).await())
                }

                // randomly get a snode from the pool
                val currentNode = pollPool.random()

                // remove that snode from the pool
                pollPool.remove(currentNode)

                poll(currentNode)
                retryScalingFactor = 1f

                requestTokens.forEach { it.trySend(Result.success(Unit)) }
            }
            catch (e: CancellationException) {
                Log.w(TAG, "Polling cancelled", e)
                requestTokens.forEach { it.trySend(Result.failure(e)) }
                throw e
            }
            catch (e: Exception){
                Log.e(TAG, "Error while polling:", e)
                pollDelay = minOf(MAX_RETRY_INTERVAL_MS, (RETRY_INTERVAL_MS * (NEXT_RETRY_MULTIPLIER * retryScalingFactor)).toLong())
                retryScalingFactor++
                requestTokens.forEach { it.trySend(Result.failure(e)) }
            } finally {
                collector.emit(PollState.Idle)
            }

            scheduledNextPoll = SystemClock.elapsedRealtime() + pollDelay
        }
    }

    private fun processPersonalMessages(snode: Snode, rawMessages: RawResponse) {
        val messages = SnodeAPI.parseRawMessagesResponse(rawMessages, snode, userPublicKey)
        val parameters = messages.map { (envelope, serverHash) ->
            MessageReceiveParameters(envelope.toByteArray(), serverHash = serverHash)
        }
        parameters.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { chunk ->
            val job = BatchMessageReceiveJob(chunk)
            JobQueue.shared.add(job)
        }
    }

    private fun processConfig(snode: Snode, rawMessages: RawResponse, forConfig: UserConfigType) {
        val messages = rawMessages["messages"] as? List<*>
        val namespace = forConfig.namespace
        val processed = if (!messages.isNullOrEmpty()) {
            SnodeAPI.updateLastMessageHashValueIfPossible(snode, userPublicKey, messages, namespace)
            SnodeAPI.removeDuplicates(
                publicKey = userPublicKey,
                messages = messages,
                messageHashGetter = { (it as? Map<*, *>)?.get("hash") as? String },
                namespace = namespace,
                updateStoredHashes = true
            ).mapNotNull { rawMessageAsJSON ->
                rawMessageAsJSON as Map<*, *> // removeDuplicates should have ensured this is always a map
                val hashValue = rawMessageAsJSON["hash"] as? String ?: return@mapNotNull null
                val b64EncodedBody = rawMessageAsJSON["data"] as? String ?: return@mapNotNull null
                val timestamp = rawMessageAsJSON["t"] as? Long ?: SnodeAPI.nowWithOffset
                val body = Base64.decode(b64EncodedBody)
                ConfigMessage(data = body, hash = hashValue, timestamp = timestamp)
            }
        } else emptyList()

        if (processed.isEmpty()) return

        Log.i(TAG, "Processing ${processed.size} messages for $forConfig")

        try {
            configFactory.mergeUserConfigs(
                userConfigType = forConfig,
                messages = processed,
            )
        } catch (e: Exception) {
            Log.e(TAG, e)
        }
    }


    private suspend fun poll(snode: Snode) {
        val userAuth = requireNotNull(MessagingModuleConfiguration.shared.storage.userAuth)
        val requestSparseArray = SparseArray<SnodeAPI.SnodeBatchRequestInfo>()
        // get messages
        SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
            lastHash = lokiApiDatabase.getLastMessageHashValue(
                snode = snode,
                publicKey = userAuth.accountId.hexString,
                namespace = Namespace.DEFAULT()
            ),
            auth = userAuth,
            maxSize = -2)
            .also { personalMessages ->
            // namespaces here should always be set
            requestSparseArray[personalMessages.namespace!!] = personalMessages
        }
        // get the latest convo info volatile
        val hashesToExtend = mutableSetOf<String>()
        configFactory.withUserConfigs { configs ->
            UserConfigType
                .entries
                .map { type ->
                    val config = configs.getConfig(type)
                    hashesToExtend += config.activeHashes()
                    type.namespace to SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
                        lastHash = lokiApiDatabase.getLastMessageHashValue(
                            snode = snode,
                            publicKey = userAuth.accountId.hexString,
                            namespace = type.namespace
                        ),
                        auth = userAuth,
                        namespace = type.namespace,
                        maxSize = -8
                    )
                }
        }.forEach { (namespace, request) ->
            // namespaces here should always be set
            requestSparseArray[namespace] = request
        }

        val requests = requestSparseArray.valueIterator().asSequence().toMutableList()

        if (hashesToExtend.isNotEmpty()) {
            SnodeAPI.buildAuthenticatedAlterTtlBatchRequest(
                messageHashes = hashesToExtend.toList(),
                auth = userAuth,
                newExpiry = SnodeAPI.nowWithOffset + 14.days.inWholeMilliseconds,
                extend = true
            ).let { extensionRequest ->
                requests += extensionRequest
            }
        }

        if (requests.isNotEmpty()) {
            val rawResponses = SnodeAPI.getRawBatchResponse(snode, userPublicKey, requests).await()
            val responseList = (rawResponses[KEY_RESULTS] as List<RawResponse>)
            // in case we had null configs, the array won't be fully populated
            // index of the sparse array key iterator should be the request index, with the key being the namespace
            UserConfigType.entries
                .map { type -> type to requestSparseArray.indexOfKey(type.namespace) }
                .filter { (_, i) -> i >= 0 }
                .forEach { (configType, requestIndex) ->
                    responseList.getOrNull(requestIndex)?.let { rawResponse ->
                        if (rawResponse[KEY_CODE] as? Int != 200) {
                            Log.e(TAG, "Batch sub-request had non-200 response code, returned code ${(rawResponse[KEY_CODE] as? Int) ?: "[unknown]"}")
                            return@forEach
                        }
                        val body = rawResponse[KEY_BODY] as? RawResponse
                        if (body == null) {
                            Log.e(TAG, "Batch sub-request didn't contain a body")
                            return@forEach
                        }

                        processConfig(snode, body, configType)
                    }
                }

            // the first response will be the personal messages (we want these to be processed after config messages)
            val personalResponseIndex = requestSparseArray.indexOfKey(Namespace.DEFAULT())
            if (personalResponseIndex >= 0) {
                responseList.getOrNull(personalResponseIndex)?.let { rawResponse ->
                    if (rawResponse[KEY_CODE] as? Int != 200) {
                        // If we got a non-success response then the snode might be bad
                        throw(RuntimeException("Batch sub-request for personal messages had non-200 response code, returned code ${(rawResponse[KEY_CODE] as? Int) ?: "[unknown]"}"))
                    } else {
                        val body = rawResponse[KEY_BODY] as? RawResponse
                        if (body == null) {
                            throw(RuntimeException("Batch sub-request for personal messages didn't contain a body"))
                        } else {
                            processPersonalMessages(snode, body)
                        }
                    }
                }
            } else {
                throw(SnodeAPI.Error.Generic)
            }
        }
    }
}
