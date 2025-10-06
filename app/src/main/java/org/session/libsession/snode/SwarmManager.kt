package org.session.libsession.snode

import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Snode
import javax.inject.Singleton

@Singleton
class SwarmManager {
    suspend fun getRandomSwarmNode(swarmAddress: AccountId): Snode {
        TODO()
    }

    suspend fun handleExceptionOnNodeRPC(
        swarmAddress: AccountId,
        snode: Snode,
        exception: Exception
    ) {
        TODO()
    }
}