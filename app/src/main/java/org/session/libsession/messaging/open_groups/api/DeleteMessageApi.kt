package org.session.libsession.messaging.open_groups.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class DeleteMessageApi @AssistedInject constructor(
    @Assisted override val room: String,
    @Assisted private val messageId: Long,
    deps: CommunityApiDependencies
) : CommunityApi<Unit>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "DELETE"
    override val httpEndpoint: String = "/room/$room/message/$messageId"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ) = Unit

    @AssistedFactory
    interface Factory {
        fun create(
            room: String,
            messageId: Long
        ): DeleteMessageApi
    }
}