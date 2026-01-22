package org.session.libsession.messaging.open_groups.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.network.ServerClientErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.auth.LoginStateRepository
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Provider

abstract class CommunityApi<ResponseType>(
    errorManager: ServerClientErrorManager,
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
    abstract val responseDeserializer: DeserializationStrategy<ResponseType>
    abstract val httpEndpoint: String

    open fun buildRequestBody(serverBaseUrl: String, x25519PubKeyHex: String): Pair<MediaType, ByteArray>? = null

    override fun buildRequest(baseUrl: String, x25519PubKeyHex: String): Request {
        val builder = Request.Builder()

        val body = buildRequestBody(baseUrl, x25519PubKeyHex)
        val url = baseUrl.toHttpUrl()

        builder.method(
            httpMethod,
            body?.let { (mediaType, bytes) -> bytes.toRequestBody(mediaType) })
            .url(requireNotNull(url.resolve(httpEndpoint)) {
                "Failed to resolve URL for OpenGroupApi: base=${url}, endpoint=$httpEndpoint"
            })

        if (requiresSigning) {
            val loggedInState = loginStateRepository.get().requireLoggedInState()
            val bodyHash = body?.let { Hash.hash64(it.second) } ?: byteArrayOf()
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
            val timestamp = clock.currentTimeMillis().toString()

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
                    BlindKeyAPI.blind15KeyPair(
                        ed25519SecretKey = loggedInState.accountEd25519KeyPair.secretKey.data,
                        serverPubKey = Hex.fromStringCondensed(x25519PubKeyHex)
                    ).pubKey.data
                ).hexString

                signature = BlindKeyAPI.blind15Sign(
                    ed25519SecretKey = loggedInState.accountEd25519KeyPair.secretKey.data,
                    serverPubKey = x25519PubKeyHex,
                    message = messageToSign
                )
            } else {
                pubKeyHexUsedToSign = loggedInState.accountId.hexString
                signature = ED25519.sign(
                    ed25519PrivateKey = loggedInState.accountEd25519KeyPair.secretKey.data,
                    message = messageToSign
                )
            }

            builder.addHeader("X-SOGS-Nonce", Base64.encodeBytes(nonce))
                .addHeader("X-SOGS-Timestamp", timestamp)
                .addHeader("X-SOGS-PubKey", pubKeyHexUsedToSign)
                .addHeader("X-SOGS-Signature", Base64.encodeBytes(signature))
        }

        return builder.build()
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: Response
    ): ResponseType {
        @Suppress("OPT_IN_USAGE")
        return json.decodeFromStream(responseDeserializer, response.body.byteStream())
    }

    class CommunityApiDependencies @Inject constructor(
        val errorManager: ServerClientErrorManager,
        val json: Json,
        val loginStateRepository: Provider<LoginStateRepository>,
        val configFactory: ConfigFactoryProtocol,
        val storage: StorageProtocol,
        val clock: SnodeClock,
    )
}