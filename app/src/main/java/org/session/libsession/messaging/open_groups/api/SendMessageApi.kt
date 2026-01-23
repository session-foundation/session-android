package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.MediaType
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsignal.utilities.Base64
import org.thoughtcrime.securesms.api.http.HttpBody

class SendMessageApi @AssistedInject constructor(
    @Assisted override val room: String?,
    @Assisted val message: OpenGroupMessage,
    @Assisted val fileIds: List<String>,
    deps: CommunityApiDependencies,
) : CommunityApi<SendMessageApi.Response>(deps) {

    override val requiresSigning: Boolean
        get() = true

    override val httpMethod: String
        get() = "POST"

    override val responseDeserializer: DeserializationStrategy<Response>
        get() = Response.serializer()

    override val httpEndpoint: String = "room/$room/message"

    override fun buildRequestBody(
        serverBaseUrl: String,
        x25519PubKeyHex: String
    ): Pair<MediaType, HttpBody>? {
        val signedMessage = if (!message.base64EncodedData.isNullOrEmpty()) {
            val caps = storage.getServerCapabilities(serverBaseUrl)
            val ed25519SecretKey = loginStateRepository.get()
                .requireLoggedInState().accountEd25519KeyPair.secretKey.data
            val signature = if (caps?.contains(OpenGroupApi.Capability.BLIND.name.lowercase()) == true) {
                BlindKeyAPI.blind15Sign(
                    ed25519SecretKey = ed25519SecretKey,
                    serverPubKey = x25519PubKeyHex,
                    message = Base64.decode(message.base64EncodedData)
                )
            } else {
                ED25519.sign(
                    ed25519PrivateKey = ed25519SecretKey,
                    message = Base64.decode(message.base64EncodedData)
                )
            }

            message.copy(base64EncodedSignature = Base64.encodeBytes(signature))
        } else {
            message
        }

        return super.buildRequestBody(serverBaseUrl, x25519PubKeyHex)
    }

    private class Message(
        val data: String,
        val timestampSeconds: Long,
        val signature: String,
        val sender: String,
    )

    @Serializable
    class Response(
        val id: Long,

        @SerialName("posted")
        val postedSeconds: Double,
    )
}