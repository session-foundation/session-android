package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.session.libsession.messaging.open_groups.OpenGroupApi

class GetRoomMessagesApi @AssistedInject constructor(
    @Assisted override val room: String,
    @Assisted sinceMessageId: Long?,
    @Assisted reactors: Int?,
    deps: CommunityApiDependencies,
) : CommunityApi<List<OpenGroupApi.Message>>(deps) {
    override val httpEndpoint: String = buildString {
        append("/room/")
        append(room)
        append("/messages")

        if (sinceMessageId != null) {
            append("/since/")
            append(sinceMessageId)
        }

        append("?t=r") // Not sure what 't=r' means, but it's in the original code
        if (reactors != null) {
            append("&reactors=")
            append(reactors)
        }
    }

    override val requiresSigning: Boolean
        get() = false

    override val httpMethod: String
        get() = "GET"

    override val responseDeserializer: DeserializationStrategy<List<OpenGroupApi.Message>>
        get() = ListSerializer(OpenGroupApi.Message.serializer())

    @AssistedFactory
    interface Factory {
        fun create(
            room: String,
            sinceLastId: Long?,
            reactors: Int? = 5
        ): GetRoomMessagesApi
    }
}
