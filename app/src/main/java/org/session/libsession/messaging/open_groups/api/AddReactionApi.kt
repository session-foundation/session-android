package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse

class AddReactionApi @AssistedInject constructor(
    @Assisted("room") override val room: String,
    @Assisted val messageId: Long,
    @Assisted val emoji: String,
    deps: CommunityApiDependencies,
) : CommunityApi<OpenGroupApi.AddReactionResponse>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "PUT"
    override val httpEndpoint: String = buildString {
        append("/room/")
        append(Uri.encode(room))
        append("/reaction/")
        append(messageId)
        append("/")
        append(Uri.encode(emoji))
    }

    override fun buildRequestBody(
        serverBaseUrl: String,
        x25519PubKeyHex: String
    ): Pair<MediaType, HttpBody> {
        return buildJsonRequestBody(JsonObject(emptyMap()))
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): OpenGroupApi.AddReactionResponse {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream()
            .use(json::decodeFromStream)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("room") room: String,
            messageId: Long,
            emoji: String
        ): AddReactionApi
    }
}