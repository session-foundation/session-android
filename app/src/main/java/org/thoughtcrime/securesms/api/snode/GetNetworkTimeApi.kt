package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.thoughtcrime.securesms.api.ApiExecutorContext
import java.time.Instant
import javax.inject.Inject

class GetNetworkTimeApi @Inject constructor(
    errorManager: SnodeApiErrorManager,
) : AbstractSnodeApi<Instant>(
    errorManager
) {
    override fun deserializeSuccessResponse(
        ctx: ApiExecutorContext,
        body: JsonElement
    ): Instant {
        body as JsonObject
        return Instant.ofEpochMilli((body["timestamp"] as JsonPrimitive).long)
    }

    override val methodName: String get() = "info"
    override fun buildParams(ctx: ApiExecutorContext): JsonElement = JsonObject(emptyMap())
}