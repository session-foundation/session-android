package org.thoughtcrime.securesms.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.messaging.sending_receiving.notifications.Server
import org.session.libsession.messaging.sending_receiving.notifications.SubscriptionRequest
import org.session.libsession.messaging.sending_receiving.notifications.SubscriptionResponse
import org.session.libsession.messaging.sending_receiving.notifications.UnsubscribeResponse
import org.session.libsession.messaging.sending_receiving.notifications.UnsubscriptionRequest
import org.session.libsession.network.ServerClient
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.onion.Version
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.Device
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.auth.LoginStateRepository
import javax.inject.Inject
import javax.inject.Singleton

typealias SignedSubscriptionRequest = JsonObject
typealias SignedUnsubscriptionRequest = JsonObject

@Singleton
class PushRegistryV2 @Inject constructor(
    private val device: Device,
    private val clock: SnodeClock,
    private val loginStateRepository: LoginStateRepository,
    private val serverClient: ServerClient
) {

    suspend fun register(
        requestsFactory: () -> Collection<Result<SignedSubscriptionRequest>>
    ): List<Result<SubscriptionResponse>> {
        return sendRequest("subscribe", requestsFactory)
    }

    fun buildRegisterRequest(
        token: String,
        swarmAuth: SwarmAuth,
        namespaces: List<Int>
    ): SignedSubscriptionRequest {
        val timestamp = clock.currentTimeSeconds()
        val publicKey = swarmAuth.accountId.hexString
        val sortedNamespace = namespaces.sorted()
        val signed = swarmAuth.sign(
            "MONITOR${publicKey}${timestamp}1${sortedNamespace.joinToString(separator = ",")}".encodeToByteArray()
        )

        return SubscriptionRequest(
            pubkey = publicKey,
            session_ed25519 = swarmAuth.ed25519PublicKeyHex,
            namespaces = sortedNamespace,
            data = true, // only permit data subscription for now (?)
            service = device.service,
            sig_ts = timestamp,
            service_info = mapOf("token" to token),
            enc_key = loginStateRepository.requireLoggedInState().notificationKey.data.toHexString(),
        ).let(Json::encodeToJsonElement).jsonObject + signed
    }

    suspend fun unregister(
        requestsFactory: () -> Collection<Result<SignedUnsubscriptionRequest>>
    ): List<Result<UnsubscribeResponse>> {
        return sendRequest("unsubscribe", requestsFactory)
    }

    fun buildUnregisterRequest(
        token: String,
        swarmAuth: SwarmAuth
    ): SignedUnsubscriptionRequest {
        val publicKey = swarmAuth.accountId.hexString
        val timestamp = clock.currentTimeSeconds()
        // if we want to support passing namespace list, here is the place to do it
        val signature = swarmAuth.sign(
            "UNSUBSCRIBE${publicKey}${timestamp}".encodeToByteArray()
        )

        return UnsubscriptionRequest(
            pubkey = publicKey,
            session_ed25519 = swarmAuth.ed25519PublicKeyHex,
            service = device.service,
            sig_ts = timestamp,
            service_info = mapOf("token" to token),
        ).let(Json::encodeToJsonElement).jsonObject + signature
    }

    private operator fun JsonObject.plus(additional: Map<String, String>): JsonObject {
        return JsonObject(buildMap {
            putAll(this@plus)
            for ((key, value) in additional) {
                put(key, JsonPrimitive(value))
            }
        })
    }

    private suspend inline fun <reified Request, reified Response> sendRequest(
        path: String,
        crossinline builder: () -> Collection<Result<Request>>
    ): List<Result<Response>> {
        val server = Server.LATEST
        val url = "${server.url}/$path"

        val (intermediateResults, rawApiResponse) = serverClient.sendWithData(
            operationName = "PushRegistryV2.$path",
            requestFactory = {
                val requests = builder()
                val results = ArrayList<Result<Response>?>(requests.size)

                val successfullyBuiltRequests = arrayListOf<Request>()

                for (requestBuildResult in requests) {
                    if (requestBuildResult.isSuccess) {
                        successfullyBuiltRequests.add(requestBuildResult.getOrThrow())
                        results.add(null) // placeholder for now
                    } else {
                        results.add(Result.failure(requestBuildResult.exceptionOrNull()!!))
                    }
                }

                val bodyString = Json.encodeToString(successfullyBuiltRequests)
                val body = bodyString.toRequestBody("application/json".toMediaType())
                results to Request.Builder().url(url).post(body).build()
            },
            serverBaseUrl = server.url,
            x25519PublicKey = server.publicKey,
            version = Version.V4
        )

        @Suppress("OPT_IN_USAGE") val apiResponses = withContext(Dispatchers.Default) {
            requireNotNull(rawApiResponse.body) { "Response doesn't have a body" }
                .inputStream()
                .use { Json.decodeFromStream<List<Response>>(it) }
        }

        val apiResponseIterator = apiResponses.iterator()

        intermediateResults.forEachIndexed { idx, result ->
            if (result == null) {
                // this was a successfully built request, meaning we will get a corresponding API result
                intermediateResults[idx] = Result.success(apiResponseIterator.next())
            }
        }

        check(!apiResponseIterator.hasNext()) {
            "API returned more results than expected"
        }

        @Suppress("UNCHECKED_CAST")
        return intermediateResults as List<Result<Response>>
    }
}
