package org.thoughtcrime.securesms.api

interface ApiExecutor<Req, Res> {
    suspend fun send(ctx: ApiExecutorContext, req: Req): Res
}
