package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.session.libsession.messaging.open_groups.OpenGroupApi

class GetDirectMessagesApi @AssistedInject constructor(
    @Assisted inboxOrOutbox: Boolean,
    @Assisted sinceLastId: Long?,
    deps: CommunityApiDependencies,
) : CommunityApi<List<OpenGroupApi.DirectMessage>>(deps) {
    override val room: String?
        get() = null

    override val requiresSigning: Boolean
        get() = true

    override val httpEndpoint: String = when {
        inboxOrOutbox && sinceLastId == null -> "/inbox"
        inboxOrOutbox && sinceLastId != null -> "/inbox/since/$sinceLastId"
        !inboxOrOutbox && sinceLastId == null -> "/outbox"
        else /* !isInboxOrOutbox && sinceSeqNo != null */ -> "/outbox/since/$sinceLastId"
    }

    override val httpMethod: String
        get() = "GET"

    override val responseDeserializer: DeserializationStrategy<List<OpenGroupApi.DirectMessage>>
        get() = ListSerializer(OpenGroupApi.DirectMessage.serializer())

    @AssistedFactory
    interface Factory {
        fun create(
            inboxOrOutbox: Boolean,
            sinceLastId: Long?
        ): GetDirectMessagesApi
    }
}
