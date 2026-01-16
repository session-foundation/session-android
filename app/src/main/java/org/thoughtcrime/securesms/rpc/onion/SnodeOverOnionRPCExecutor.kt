package org.thoughtcrime.securesms.rpc.onion

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.onion.Version
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.rpc.SnodeRPCExecutor
import org.thoughtcrime.securesms.rpc.SnodeRPCResponse
import javax.inject.Inject


class SnodeOverOnionRPCExecutor @Inject constructor(
    private val onionExecutor: OnionRPCExecutor,
    private val json: Json,
) : SnodeRPCExecutor {
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun send(
        dest: Snode,
        req: JsonObject
    ): SnodeRPCResponse {
        val destination = OnionDestination.SnodeDestination(dest)
        val request = OnionRequest(
            payload = json.encodeToString(req).toByteArray(),
            version = Version.V3,
        )
        val response = onionExecutor.send(destination, request)

        return SnodeRPCResponse(
            code = response.code,
            body = when (val body = response.body) {
                is OnionResponseBody.Text -> json.decodeFromString(body.text)
                is OnionResponseBody.Bytes -> body.bytes.inputStream()
                    .use(json::decodeFromStream)
            }
        )
    }
}