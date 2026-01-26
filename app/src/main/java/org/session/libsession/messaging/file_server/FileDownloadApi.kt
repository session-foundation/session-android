package org.session.libsession.messaging.file_server

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class FileDownloadApi @AssistedInject constructor(
    @Assisted private val fileId: String,
    errorManager: ServerApiErrorManager,
) : ServerApi<FileDownloadApi.Response>(errorManager) {
    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        return HttpRequest(
            url = "$baseUrl/file/$fileId".toHttpUrl(),
            method = "GET",
            headers = emptyMap(),
            body = null
        )
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): Response {
        return Response(
            data = response.body,
            expires = response.parseFileServerExpiresHeader()
        )
    }

    class Response(
        val data: HttpBody,
        val expires: ZonedDateTime?
    )

    @AssistedFactory
    interface Factory {
        fun create(fileId: String): FileDownloadApi
    }

    companion object {
        fun HttpResponse.parseFileServerExpiresHeader(): ZonedDateTime? {
            return headers["expires"]?.let {
                ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
            }
        }
    }
}