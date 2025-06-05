package org.session.libsession.messaging.sending_receiving.pollers

import android.util.SparseArray
import androidx.core.util.valueIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.Contacts
import network.loki.messenger.libsession_util.ConversationVolatileConfig
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.UserProfile
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.Namespace
import nl.komponents.kovenant.Deferred
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.all
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.resolve
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
import org.session.libsession.snode.SnodeModule
import org.session.libsession.snode.utilities.asyncPromise
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode

private const val TAG = "Poller"

class Poller(
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val lokiApiDatabase: LokiAPIDatabaseProtocol,
    private val preferences: TextSecurePreferences,
) {
    private val userPublicKey: String
        get() = storage.getUserPublicKey().orEmpty()

    var scope: CoroutineScope? = null

    var isPolling: Boolean = false

    // region Settings
    companion object {
        private const val RETRY_INTERVAL_MS: Long      = 2  * 1000
        private const val MAX_RETRY_INTERVAL_MS: Long  = 15 * 1000
        private const val NEXT_RETRY_MULTIPLIER: Float = 1.2f // If we fail to poll we multiply our current retry interval by this (up to the above max) then try again
    }
    // endregion


    // region Public API
    fun startIfNeeded() {
        if (scope != null) { return }

        Log.d(TAG, "Started polling.")
        scope = CoroutineScope(Dispatchers.Default)
        scope?.launch {
            setUpPolling()
        }
    }

    fun stopIfNeeded() {
        Log.d(TAG, "Stopped polling.")
        scope?.cancel()
        scope = null
        isPolling = false
    }

    fun retrieveUserProfile() {
        Log.d(TAG, "Retrieving user profile. for key = $userPublicKey")
        SnodeAPI.getSwarm(userPublicKey).success {
            pollUserProfile(it.random())
        }.fail { exception ->
            Log.e(TAG, "Failed to retrieve user profile.", exception)
        }
    }
    // endregion

    // region Private API
    private suspend fun setUpPolling() {
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

        while(true){
            Log.d(TAG, "Polling...")

            isPolling = true
            var pollDelay = RETRY_INTERVAL_MS
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
            } catch (e: Exception){
                Log.e(TAG, "Error while polling:", e)
                pollDelay = minOf(MAX_RETRY_INTERVAL_MS, (RETRY_INTERVAL_MS * (NEXT_RETRY_MULTIPLIER * retryScalingFactor)).toLong())
                retryScalingFactor++
            } finally {
                isPolling = false
            }

            // wait before polling again
            delay(pollDelay)
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

    //todo we will need to modify this further to fit within the new coroutine setup (currently used by ApplicationContext which is a java class)
    private fun pollUserProfile(snode: Snode) {
        val requests = mutableListOf<SnodeAPI.SnodeBatchRequestInfo>()
        val hashesToExtend = mutableSetOf<String>()
        val userAuth = requireNotNull(MessagingModuleConfiguration.shared.storage.userAuth)

        configFactory.withUserConfigs {
            hashesToExtend += it.userProfile.activeHashes()
        }

        requests += SnodeAPI.buildAuthenticatedRetrieveBatchRequest(
            lastHash = lokiApiDatabase.getLastMessageHashValue(
                snode = snode,
                publicKey = userAuth.accountId.hexString,
                namespace = Namespace.USER_PROFILE()
            ),
            auth = userAuth,
            namespace = Namespace.USER_PROFILE(),
            maxSize = -8
        )

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
            SnodeAPI.getRawBatchResponse(snode, userPublicKey, requests).bind { rawResponses ->

                val responseList = (rawResponses[KEY_RESULTS] as List<RawResponse>)
                responseList.getOrNull(0)?.let { rawResponse ->
                    if (rawResponse[KEY_CODE] as? Int != 200) {
                        Log.e(TAG, "Batch sub-request had non-200 response code, returned code ${(rawResponse[KEY_CODE] as? Int) ?: "[unknown]"}")
                    } else {
                        val body = rawResponse[KEY_BODY] as? RawResponse
                        if (body == null) {
                            Log.e(TAG, "Batch sub-request didn't contain a body")
                        } else {
                            processConfig(snode, body, UserConfigType.USER_PROFILE)
                        }
                    }
                }

                Promise.ofSuccess(Unit)
            }.fail {
                Log.e(TAG, "Failed to get raw batch response", it)
            }
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
