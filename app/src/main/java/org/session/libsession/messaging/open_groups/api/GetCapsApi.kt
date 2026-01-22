package org.session.libsession.messaging.open_groups.api

import kotlinx.serialization.DeserializationStrategy
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capabilities
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

    override val responseDeserializer: DeserializationStrategy<Capabilities>
        get() = Capabilities.serializer()

    override val httpEndpoint: String
        get() = "/capabilities"
}