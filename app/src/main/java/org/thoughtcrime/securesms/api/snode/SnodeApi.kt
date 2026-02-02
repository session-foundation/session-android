package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.json.JsonElement
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext

typealias SnodeApiResponse = Any

interface SnodeApi<RespType : SnodeApiResponse> {
    fun buildRequest(ctx: ApiExecutorContext): SnodeJsonRequest
    suspend fun handleResponse(
        ctx: ApiExecutorContext,
        snode: Snode,
        code: Int,
        body: JsonElement?
    ): RespType
}
