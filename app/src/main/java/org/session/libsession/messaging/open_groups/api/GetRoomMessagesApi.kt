package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse

class GetRoomMessagesApi @AssistedInject constructor(
    @Assisted override val room: String,
    @Assisted sinceSeqNo: Long?,
    @Assisted reactors: Int?,
    deps: CommunityApiDependencies,
) : CommunityApi<List<OpenGroupApi.Message>>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "GET"
    override val httpEndpoint: String = buildString {
        append("/room/")
        append(Uri.encode(room))
        append("/messages")

        if (sinceSeqNo != null) {
            append("/since/")
            append(sinceSeqNo)
        } else {
            append("/recent")
        }

        append("?t=r") // Not sure what 't=r' means, but it's in the original code
        if (reactors != null) {
            append("&reactors=")
            append(reactors)
        }
    }

    override fun buildRequest(baseUrl: String, x25519PubKeyHex: String): HttpRequest {
        val request = super.buildRequest(baseUrl, x25519PubKeyHex)
        return request
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): List<OpenGroupApi.Message> {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream().use(json::decodeFromStream)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            room: String,
            sinceSeqNo: Long?,
            reactors: Int? = 5
        ): GetRoomMessagesApi
    }
}
