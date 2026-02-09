package org.session.libsession.messaging.open_groups.api

import android.net.Uri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpResponse
import okhttp3.MediaType

class BanUserApi @AssistedInject constructor(
    @Assisted("user") private val userToBan: String,
    @Assisted override val room: String,
    deps: CommunityApiDependencies,
) : CommunityApi<Unit>(deps) {
    override val requiresSigning: Boolean get() = true
    override val httpMethod: String get() = "POST"
    override val httpEndpoint: String =
        "/user/${Uri.encode(userToBan)}/ban"

    @Serializable
    private data class BanBody(val rooms: List<String>)

    override fun buildRequestBody(
        serverBaseUrl: String,
        x25519PubKeyHex: String
    ): Pair<MediaType, HttpBody> {
        // JSON body {"rooms":[room]}
        return buildJsonRequestBody(BanBody(rooms = listOf(room)))
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ) = Unit

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("user") userToBan: String,
            room: String
        ): BanUserApi
    }
}