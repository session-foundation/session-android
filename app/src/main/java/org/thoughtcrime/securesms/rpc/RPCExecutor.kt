package org.thoughtcrime.securesms.rpc

interface RPCExecutor<Dest, Req, Res> {
    suspend fun send(dest: Dest, req: Req): Res
}