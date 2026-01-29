package org.thoughtcrime.securesms.api.swarm

import androidx.collection.arraySetOf
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Snode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private typealias SwarmPubKeyHex = String

/**
 * An algorithm for selecting a snode from a swarm, ensuring that swarm snodes are used evenly and
 * randomly across multiple [selectSnode].
 */
@Singleton
class SwarmSnodeSelector @Inject constructor(
    private val swarmDirectory: SwarmDirectory,
) {
    private class SwarmSelectionState(
        val usedSnodeEd25519PubKey: MutableSet<String>,
    )

    private val selectionStates = ConcurrentHashMap<SwarmPubKeyHex, SwarmSelectionState>()

    suspend fun selectSnode(swarmPubKey: String): Snode {
        val selectionState = selectionStates.getOrPut(swarmPubKey) {
            SwarmSelectionState(arraySetOf())
        }

        val swarmNodes = swarmDirectory.getSwarm(swarmPubKey)

        check(swarmNodes.isNotEmpty()) {
            "Swarm is empty for pubkey=$swarmPubKey"
        }

        return synchronized(selectionState) {
            val available = swarmNodes.filterNot { it.ed25519Key in selectionState.usedSnodeEd25519PubKey }

            val selected = if (available.isEmpty()) {
                // All snodes have been used, reset and start over
                selectionState.usedSnodeEd25519PubKey.clear()
                swarmNodes.random()
            } else {
                available.random()
            }

            selectionState.usedSnodeEd25519PubKey += selected.ed25519Key
            selected
        }
    }
}
