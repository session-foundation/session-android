package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject

class PollRoomApi @AssistedInject constructor(
    @Assisted override val room: String,
    @Assisted infoUpdates: Int,
    deps: CommunityApiDependencies,
) : CommunityApi<JsonObject>(deps) {
    override val httpMethod: String
        get() = "GET"

    override val responseDeserializer: DeserializationStrategy<JsonObject>
        get() = JsonObject.serializer()

    override val httpEndpoint: String = "room/$room/pollInfo/$infoUpdates"

    override val requiresSigning: Boolean
        get() = false

    @AssistedFactory
    interface Factory {
        fun create(room: String, infoUpdates: Int): PollRoomApi
    }
}