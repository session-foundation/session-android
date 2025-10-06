package org.session.libsession.snode

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.snode.endpoint.Endpoint
import org.session.libsession.snode.model.OnionSnodeRequest
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.Snode
import java.io.ByteArrayInputStream
import javax.inject.Inject

class OnionRoutedRPCService @Inject constructor(
    private val json: Json,
) : StorageRPCService {
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun <Req, Res> call(
        snode: Snode,
        endpoint: Endpoint<Req, Res>,
        req: Req
    ): Res {
        val request = OnionSnodeRequest(
            method = endpoint.methodName,
            params = endpoint.serializeRequest(json, req)
        )

        val resp = OnionRequestAPI.sendOnionRequest(
            destination = OnionRequestAPI.Destination.Snode(snode),
            payload = json.encodeToString(request).toByteArray(),
            version = Version.V3
        ).await()

        if (resp.code == 200 || resp.code == null) {
            val body = json.decodeFromStream<JsonElement>(
                ByteArrayInputStream(
                    resp.body!!.data,
                    resp.body.offset,
                    resp.body.len
                )
            )

            return endpoint.deserializeResponse(json, body)
        } else {
            //TODO: Exception translation
            throw Exception("Onion RPC call failed with code ${resp.code}")
        }
    }
}