package org.session.libsession.messaging.file_server

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi

class FileRenewApi @AssistedInject constructor(
    @Assisted private val fileId: String,
    errorManager: ServerApiErrorManager,
) : ServerApi<Unit>(errorManager) {

    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        return HttpRequest(
            url = "$baseUrl/file/$fileId/extend".toHttpUrl(),
            method = "POST",
            headers = emptyMap(),
            body = null,
        )
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ) = Unit

    @AssistedFactory
    interface Factory {
        fun create(fileId: String): FileRenewApi
    }
}