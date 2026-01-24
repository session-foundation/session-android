package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsignal.utilities.Base64
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse

class SendMessageApi @AssistedInject constructor(
    @Assisted override val room: String,
    @Assisted val message: OpenGroupMessage,
    @Assisted val fileIds: List<String>,
    deps: CommunityApiDependencies,
) : CommunityApi<SendMessageApi.Response>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "POST"
    override val httpEndpoint: String = "room/$room/message"

    override fun buildRequestBody(
        serverBaseUrl: String,
        x25519PubKeyHex: String
    ): Pair<MediaType, HttpBody> {
        val caps = storage.getServerCapabilities(serverBaseUrl)
        val ed25519SecretKey = loginStateRepository.get()
            .requireLoggedInState().accountEd25519KeyPair.secretKey.data
        val signature = if (caps?.contains(OpenGroupApi.Capability.BLIND.name.lowercase()) == true) {
            BlindKeyAPI.blind15Sign(
                ed25519SecretKey = ed25519SecretKey,
                serverPubKey = x25519PubKeyHex,
                message = Base64.decode(message.base64EncodedData.orEmpty())
            )
        } else {
            ED25519.sign(
                ed25519PrivateKey = ed25519SecretKey,
                message = Base64.decode(message.base64EncodedData.orEmpty())
            )
        }

        return buildJsonRequestBody(Message(
            data = message.base64EncodedData.orEmpty(),
            timestampSeconds = message.sentTimestamp / 1000.0,
            signature = Base64.encodeBytes(signature),
            sender = message.sender.orEmpty(),
            files = fileIds.takeIf { it.isNotEmpty() }
        ))
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): Response {
        @Suppress("OPT_IN_USAGE")
        val response: Response = response.body.asInputStream().use(json::decodeFromStream)
        storage.addReceivedMessageTimestamp(response.postedMills)
        return response
    }

    @Serializable
    private class Message(
        val data: String,
        @SerialName("timestamp")
        val timestampSeconds: Double,
        val signature: String,
        @SerialName("public_key")
        val sender: String,
        val files: List<String>? = null,
    )

    @Serializable
    class Response(
        val id: Long,

        @SerialName("posted")
        val postedSeconds: Double,
    ) {
        val postedMills: Long
            get() = (postedSeconds * 1000.0).toLong()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            room: String,
            message: OpenGroupMessage,
            fileIds: List<String>
        ): SendMessageApi
    }
}