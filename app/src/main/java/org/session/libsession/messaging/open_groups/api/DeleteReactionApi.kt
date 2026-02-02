package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse

class DeleteReactionApi @AssistedInject constructor(
    @Assisted("room") override val room: String,
    @Assisted val messageId: Long,
    @Assisted val emoji: String,
    deps: CommunityApiDependencies,
) : CommunityApi<Unit>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "DELETE"
    override val httpEndpoint: String = buildString {
        append("/room/")
        append(Uri.encode(room))
        append("/reaction/")
        append(messageId)
        append("/")
        append(Uri.encode(emoji))
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ) = Unit

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("room") room: String,
            messageId: Long,
            emoji: String
        ): DeleteReactionApi
    }
}