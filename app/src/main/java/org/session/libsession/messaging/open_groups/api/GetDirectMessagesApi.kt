package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class GetDirectMessagesApi @AssistedInject constructor(
    @Assisted inboxOrOutbox: Boolean,
    @Assisted sinceLastId: Long?,
    deps: CommunityApiDependencies,
) : CommunityApi<List<OpenGroupApi.DirectMessage>>(deps) {
    override val room: String? get() = null
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "GET"
    override val httpEndpoint: String = when {
        inboxOrOutbox && sinceLastId == null -> "/inbox"
        inboxOrOutbox && sinceLastId != null -> "/inbox/since/$sinceLastId"
        !inboxOrOutbox && sinceLastId == null -> "/outbox"
        else /* !isInboxOrOutbox && sinceSeqNo != null */ -> "/outbox/since/$sinceLastId"
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): List<OpenGroupApi.DirectMessage> {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream().use(json::decodeFromStream)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            inboxOrOutbox: Boolean,
            sinceLastId: Long?
        ): GetDirectMessagesApi
    }
}
