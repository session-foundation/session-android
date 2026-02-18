package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class GetRoomDetailsApi @AssistedInject constructor(
    deps: CommunityApiDependencies,
    @Assisted override val room: String,
) : CommunityApi<OpenGroupApi.RoomInfoDetails>(deps) {
    override val requiresSigning: Boolean
        get() = false

    override val httpMethod: String
        get() = "GET"

    override val httpEndpoint: String
        get() =  "/room/${room}"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): OpenGroupApi.RoomInfoDetails {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream().use(json::decodeFromStream)
    }

    @AssistedFactory
    interface Factory {
        fun create(room: String): GetRoomDetailsApi
    }
}
