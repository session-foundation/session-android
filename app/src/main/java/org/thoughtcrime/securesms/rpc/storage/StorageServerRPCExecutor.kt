package org.thoughtcrime.securesms.rpc.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.rpc.RPCExecutor
import org.thoughtcrime.securesms.rpc.SnodeRPCExecutor
import javax.inject.Inject

typealias StorageSnodeRPCExecutor = RPCExecutor<Snode, StorageServiceRequest<*>, StorageServiceResponse>

class StorageServerRPCExecutorImpl @Inject constructor(
    private val executor: SnodeRPCExecutor,
) : RPCExecutor<Snode, StorageServiceRequest<*>, StorageServiceResponse> {
    override suspend fun send(
        dest: Snode,
        req: StorageServiceRequest<*>
    ): StorageServiceResponse {
        val response = executor.send(dest, req.buildRequest())
        return req.deserializeResponse(
            snode = dest,
            code = response.code,
            body = response.body
        )
    }

    private fun StorageServiceRequest<*>.buildRequest(): JsonObject {
        return JsonObject(
            mapOf("method" to JsonPrimitive(methodName), "params" to buildParams())
        )
    }
}

suspend inline fun <reified Res, Req> StorageSnodeRPCExecutor.execute(
    dest: Snode,
    req: Req
): Res where Res : StorageServiceResponse, Req : StorageServiceRequest<Res> {
    return send(dest, req) as Res
}