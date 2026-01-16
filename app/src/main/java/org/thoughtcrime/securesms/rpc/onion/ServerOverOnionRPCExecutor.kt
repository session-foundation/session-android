package org.thoughtcrime.securesms.rpc.onion

import okhttp3.Request as HttpRequest
import okhttp3.Response as HttpResponse
import org.thoughtcrime.securesms.rpc.ServerAddress
import org.thoughtcrime.securesms.rpc.ServerRPCExecutor
import javax.inject.Inject

class ServerOverOnionRPCExecutor @Inject constructor(
    private val onionRPCExecutor: OnionRPCExecutor,
): ServerRPCExecutor {
    override suspend fun send(
        dest: ServerAddress,
        req: HttpRequest
    ): HttpResponse {
        TODO("Not yet implemented")
    }
}