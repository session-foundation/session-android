package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse

class DownloadFileApi @AssistedInject constructor(
    @Assisted("room") override val room: String?,
    @Assisted val fileId: String,
    deps: CommunityApiDependencies,
) : CommunityApi<HttpBody>(deps) {
    override val requiresSigning: Boolean get() = false
    override val httpMethod: String get() = "GET"
    override val httpEndpoint: String = if (room != null) {
        "/room/${Uri.encode(room)}/file/${Uri.encode(fileId)}"
    } else {
        "/file/${Uri.encode(fileId)}"
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): HttpBody {
        return response.body
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("room") room: String?,
            fileId: String
        ): DownloadFileApi
    }
}
