package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import org.thoughtcrime.securesms.api.ApiExecutorContext
import java.time.Instant
import javax.inject.Inject

class GetInfoApi @Inject constructor(
    errorManager: SnodeApiErrorManager,
    private val json: Json,
) : AbstractSnodeApi<GetInfoApi.InfoResponse>(errorManager) {
    override fun deserializeSuccessResponse(
        ctx: ApiExecutorContext,
        body: JsonElement
    ): InfoResponse {
        return json.decodeFromJsonElement(body)
    }

    override val methodName: String get() = "info"
    override fun buildParams(ctx: ApiExecutorContext): JsonElement = JsonObject(emptyMap())


    @Serializable
    class InfoResponse(
        @Serializable(with = InstantAsMillisSerializer::class)
        val timestamp: Instant
    )
}