package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class GetRoomsApi @AssistedInject constructor(
    @Assisted override val requiresSigning: Boolean,
    deps: CommunityApiDependencies,
) : CommunityApi<List<OpenGroupApi.RoomInfoDetails>>(deps) {
    override val room: String? get() = null
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

    @AssistedFactory
    interface Factory {
        fun create(requiresSigning: Boolean): GetRoomsApi
    }
}