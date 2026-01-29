package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse

class CommunityFileUploadApi @AssistedInject constructor(
    @Assisted private val file: HttpBody,
    @Assisted override val room: String,
    deps: CommunityApiDependencies
) : CommunityApi<String>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "POST"
    override val httpEndpoint: String = "/room/${Uri.encode(room)}/file"

    override fun buildRequest(baseUrl: String, x25519PubKeyHex: String): HttpRequest {
        val request = super.buildRequest(baseUrl, x25519PubKeyHex)
        return request.copy(
            headers = request.headers.toMutableMap().apply {
                this["Content-Type"] = "application/octet-stream"
                this["Content-Disposition"] = "attachment"
            },
            body = file,
        )
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): String {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream()
            .use { json.decodeFromStream<UploadResult>(it) }
            .id
    }

    @Serializable
    private class UploadResult(
        val id: String
    )

    @AssistedFactory
    interface Factory {
        fun create(
            file: HttpBody,
            room: String
        ): CommunityFileUploadApi
    }
}