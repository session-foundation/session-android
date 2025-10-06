package org.session.libsession.snode

import kotlinx.coroutines.CancellationException
import org.session.libsession.snode.endpoint.Endpoint
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnodeService @Inject constructor(
    private val storageRPCService: StorageRPCService,
    private val swarmManager: SwarmManager,
) {
    suspend fun <Req, Res> invokeOnSwarm(
        swarmAddress: AccountId,
        endpoint: Endpoint<Req, Res>,
        req: Req
    ): Res {
        val node = swarmManager.getRandomSwarmNode(swarmAddress)
        Log.d(TAG, "Invoking ${endpoint.methodName} on ${node.address}")
        try {
            return storageRPCService.call(node, endpoint, req)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Error invoking ${endpoint.methodName} on ${node.address}", e)

                swarmManager.handleExceptionOnNodeRPC(swarmAddress, node, e)
            }

            throw e
        }
    }

    companion object {
        private const val TAG = "SnodeService"
    }
}