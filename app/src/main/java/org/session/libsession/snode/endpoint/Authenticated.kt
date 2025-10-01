package org.session.libsession.snode.endpoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.SwarmAuth

class Authenticated<Req : WithSigningData, Res>(
    private val clock: SnodeClock,
    private val realEndpoint: Endpoint<Req, Res>,
    private val swarmAuth: SwarmAuth,
) : Endpoint<Req, Res> by realEndpoint {
    override fun serializeRequest(
        json: Json,
        request: Req
    ): JsonObject {
        val element = (realEndpoint.serializeRequest(json, request) as JsonObject).toMutableMap()
        val timestampMs = clock.currentTimeMills()
        val data = request.getSigningData(timestampMs)

        for ((key, value) in swarmAuth.sign(data)) {
            element[key] = JsonPrimitive(value)
        }

        element["timestamp"] = JsonPrimitive(timestampMs)
        element["pubkey"] = JsonPrimitive(swarmAuth.accountId.hexString)
        swarmAuth.ed25519PublicKeyHex?.let {
            element["pubkey_ed25519"] = JsonPrimitive(it)
        }

        return JsonObject(element)
    }

    override fun batchKey(request: Req): String? {
        return "${realEndpoint.batchKey(request)}-$${swarmAuth.accountId.hexString}"
    }

}