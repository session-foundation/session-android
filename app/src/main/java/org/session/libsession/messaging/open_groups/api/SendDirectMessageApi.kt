package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse

class SendDirectMessageApi @AssistedInject constructor(
    @Assisted private val recipient: Address.Blinded,
    @Assisted private val messageContent: String,
    deps: CommunityApiDependencies,
) : CommunityApi<OpenGroupApi.DirectMessage>(deps) {
    override val room: String? get() = null
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "POST"
    override val httpEndpoint: String = "/inbox/${recipient.blindedId.hexString}"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): OpenGroupApi.DirectMessage {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream().use(json::decodeFromStream)
    }

    override fun buildRequestBody(
        serverBaseUrl: String,
        x25519PubKeyHex: String
    ): Pair<MediaType, HttpBody> {
        return buildJsonRequestBody(Request(messageContent))
    }

    @Serializable
    private class Request(
        val message: String
    )

    @AssistedFactory
    interface Factory {
        fun create(
            recipient: Address.Blinded,
            messageContent: String
        ): SendDirectMessageApi
    }
}