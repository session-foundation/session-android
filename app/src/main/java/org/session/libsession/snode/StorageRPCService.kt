package org.session.libsession.snode

import org.session.libsession.snode.endpoint.Endpoint
import org.session.libsignal.utilities.Snode

interface StorageRPCService {
    suspend fun <Req, Res> call(snode: Snode, endpoint: Endpoint<Req, Res>, req: Req): Res
}