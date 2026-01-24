package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class PollRoomApi @AssistedInject constructor(
    @Assisted override val room: String,
    @Assisted infoUpdates: Int,
    deps: CommunityApiDependencies,
) : CommunityApi<JsonObject>(deps) {
    override val httpMethod: String
        get() = "GET"

    override val httpEndpoint: String = "room/$room/pollInfo/$infoUpdates"

    override val requiresSigning: Boolean
        get() = false

    @Suppress("OPT_IN_USAGE")
    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): JsonObject = response.body.asInputStream().use(json::decodeFromStream)

    @AssistedFactory
    interface Factory {
        fun create(room: String, infoUpdates: Int): PollRoomApi
    }
}