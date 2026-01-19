package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext

interface SnodeApiResponse

interface SnodeApi<RespType : SnodeApiResponse> {
    val methodName: String
    fun buildParams(): JsonObject
    suspend fun handleResponse(ctx: ApiExecutorContext, snode: Snode, code: Int, body: JsonElement?): RespType
}
