package org.thoughtcrime.securesms.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.session.libsignal.utilities.Snode


class SnodeRPCResponse(
    val code: Int,
    val body: JsonElement?
)

typealias SnodeRPCExecutor = RPCExecutor<Snode, JsonObject, SnodeRPCResponse>