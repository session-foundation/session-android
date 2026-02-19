package org.session.libsession.messaging.open_groups.api

import androidx.collection.arrayMapOf
import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.model.FailureDecision
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.auth.LoginStateRepository
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Provider

abstract class CommunityApi<ResponseType: Any>(
    errorManager: ServerApiErrorManager,
    protected val json: Json,
    protected val loginStateRepository: Provider<LoginStateRepository>,
    protected val configFactory: ConfigFactoryProtocol,
    protected val storage: StorageProtocol,
    protected val clock: SnodeClock,
) : ServerApi<ResponseType>(
    errorManager = errorManager,
) {
    constructor(deps: CommunityApiDependencies) : this(
        errorManager = deps.errorManager,
        json = deps.json,
        loginStateRepository = deps.loginStateRepository,
        configFactory = deps.configFactory,
        storage = deps.storage,
        clock = deps.clock,
    )


    // The room ID associated with this API call
    abstract val room: String?

    abstract val requiresSigning: Boolean

    abstract val httpMethod: String
    abstract val httpEndpoint: String

    open fun buildRequestBody(serverBaseUrl: String, x25519PubKeyHex: String): Pair<MediaType, HttpBody>? = null

    protected inline fun <reified T> buildJsonRequestBody(obj: T): Pair<MediaType, HttpBody> {
        return "application/json".toMediaType() to HttpBody.Text(json.encodeToString(obj))
    }

    override fun buildRequest(baseUrl: String, x25519PubKeyHex: String): HttpRequest {
        val builtBody = buildRequestBody(baseUrl, x25519PubKeyHex)
        val headers = arrayMapOf<String, String>()

        val httpBody = if (builtBody != null) {
            headers["Content-Type"] = builtBody.first.toString()
            headers["Content-Length"] = builtBody.second.byteLength.toString()
            builtBody.second
        } else {
            null
        }

        room?.let {
            headers["Room"] = it
        }

        if (requiresSigning) {
            val loggedInState = loginStateRepository.get().requireLoggedInState()
            val bodyHash = builtBody?.let { Hash.hash64(it.second.toBytes()) } ?: byteArrayOf()
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
            val timestamp = clock.currentTimeSeconds().toString()

            val messageToSign = ByteArrayOutputStream().use { stream ->
                stream.write(Hex.fromStringCondensed(x25519PubKeyHex))
                stream.write(nonce)

                stream.writer().use { w ->
                    w.write(timestamp)
                    w.write(httpMethod)
                    w.write(httpEndpoint)
                }

                stream.write(bodyHash)
                stream.toByteArray()
            }

            val pubKeyHexUsedToSign: String
            val signature: ByteArray

            if (storage.getServerCapabilities(baseUrl)
                ?.contains(OpenGroupApi.Capability.BLIND.name.lowercase()) == true) {
                pubKeyHexUsedToSign = AccountId(
                    IdPrefix.BLINDED,
                    loggedInState.getBlindedKeyPair(baseUrl, x25519PubKeyHex).pubKey.data
                 ).hexString

                signature = BlindKeyAPI.blind15Sign(
                    ed25519SecretKey = loggedInState.accountEd25519KeyPair.secretKey.data,
                    serverPubKey = x25519PubKeyHex,
                    message = messageToSign
                )
            } else {
                pubKeyHexUsedToSign = AccountId(
                    IdPrefix.UN_BLINDED,
                    loggedInState.accountEd25519KeyPair.pubKey.data
                ).hexString

                signature = ED25519.sign(
                    ed25519PrivateKey = loggedInState.accountEd25519KeyPair.secretKey.data,
                    message = messageToSign
                )
            }

            headers["X-SOGS-Nonce"] = Base64.encodeBytes(nonce)
            headers["X-SOGS-Timestamp"] = timestamp
            headers["X-SOGS-PubKey"] = pubKeyHexUsedToSign
            headers["X-SOGS-Signature"] = Base64.encodeBytes(signature)
        }

        return HttpRequest(
            url = requireNotNull(baseUrl.toHttpUrl().resolve(httpEndpoint)) {
                "Could not resolve URL for endpoint $httpEndpoint with base $baseUrl"
            },
            method = httpMethod,
            headers = headers,
            body = httpBody,
        )
    }


    override suspend fun handleErrorResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): Nothing {
        if (response.statusCode == 400 &&
            response.body.toText()?.contains("Invalid authentication: this server requires the use of blinded ids", ignoreCase = true) == true) {
            storage.clearServerCapabilities(baseUrl)
            throw ErrorWithFailureDecision(
                cause = RuntimeException("Server requires blinded ids"),
                failureDecision = FailureDecision.Retry,
            )
        }

        super.handleErrorResponse(executorContext, baseUrl, response)
    }

    override fun debugInfo(): String {
        return "${this.javaClass.simpleName} for room \"$room\""
    }

    class CommunityApiDependencies @Inject constructor(
        val errorManager: ServerApiErrorManager,
        val json: Json,
        val loginStateRepository: Provider<LoginStateRepository>,
        val configFactory: ConfigFactoryProtocol,
        val storage: StorageProtocol,
        val clock: SnodeClock,
    )
}