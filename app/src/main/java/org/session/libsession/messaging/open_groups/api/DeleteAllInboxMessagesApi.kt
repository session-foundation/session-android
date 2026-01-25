package org.session.libsession.messaging.open_groups.api

import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse
import javax.inject.Inject

class DeleteAllInboxMessagesApi @Inject constructor(
    deps: CommunityApiDependencies,
): CommunityApi<Unit>(deps) {
    override val room: String? get() = null
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "DELETE"
    override val httpEndpoint: String get() = "/inbox"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ) = Unit
}