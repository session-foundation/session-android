package org.session.libsession.messaging.open_groups.api

import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse
import javax.inject.Inject

class GetRoomsApi @Inject constructor(
    deps: CommunityApiDependencies,
) : CommunityApi<List<OpenGroupApi.RoomInfoDetails>>(deps) {
    override val room: String? get() = null
    override val requiresSigning: Boolean get() = false
    override val httpMethod: String get() = "GET"
    override val httpEndpoint: String get() = "/rooms"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): List<OpenGroupApi.RoomInfoDetails> {
        @Suppress("OPT_IN_USAGE")
        return json.decodeFromStream(response.body.asInputStream())
    }
}