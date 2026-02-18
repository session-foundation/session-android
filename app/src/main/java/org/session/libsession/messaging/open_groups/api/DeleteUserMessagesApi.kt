package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class DeleteUserMessagesApi @AssistedInject constructor(
    @Assisted("user") private val userToDelete: String,
    @Assisted override val room: String,
    deps: CommunityApiDependencies,
) : CommunityApi<Unit>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "DELETE"
    override val httpEndpoint: String =
        "/room/${Uri.encode(room)}/all/${Uri.encode(userToDelete)}"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ) = Unit

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("user") userToDelete: String,
            room: String
        ): DeleteUserMessagesApi
    }
}