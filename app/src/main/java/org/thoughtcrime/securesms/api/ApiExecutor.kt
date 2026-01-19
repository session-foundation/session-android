package org.thoughtcrime.securesms.api

interface ApiExecutor<Dest, Req, Res> {
    suspend fun send(ctx: ApiExecutorContext, dest: Dest, req: Req): Res
}