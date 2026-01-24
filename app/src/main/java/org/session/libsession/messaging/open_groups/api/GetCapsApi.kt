package org.session.libsession.messaging.open_groups.api

import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capabilities
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpResponse
import javax.inject.Inject

class GetCapsApi @Inject constructor(
    deps: CommunityApiDependencies,
) : CommunityApi<Capabilities>(deps) {
    override val room: String?
        get() = null

    override val requiresSigning: Boolean
        get() = false

    override val httpMethod: String
        get() = "GET"

    override val httpEndpoint: String
        get() = "/capabilities"

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): Capabilities {
        @Suppress("OPT_IN_USAGE")
        return response.body.asInputStream().use(json::decodeFromStream)
    }
}