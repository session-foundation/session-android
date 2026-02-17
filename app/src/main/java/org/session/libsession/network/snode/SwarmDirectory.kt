package org.session.libsession.network.snode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.session.libsignal.crypto.shuffledRandom
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.GetSwarmApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.execute
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SwarmDirectory @Inject constructor(
    private val storage: SwarmStorage,
    private val snodeDirectory: SnodeDirectory,
    private val snodeApiExecutor: Provider<SnodeApiExecutor>,
    private val getSwarmFactory: GetSwarmApi.Factory,
    private val json: Json,
) {
    private val minimumSwarmSize: Int = 3

    suspend fun getSwarm(publicKey: String): List<Snode> {
        val cached = storage.getSwarm(publicKey)
        if (cached.size >= minimumSwarmSize) {
            return cached
        }

        val fresh = fetchSwarm(publicKey)
        storage.setSwarm(publicKey, fresh)
        return fresh
    }

    suspend fun fetchSwarm(publicKey: String): List<Snode> {
        val pool = snodeDirectory.ensurePoolPopulated()
        require(pool.isNotEmpty()) {
            "Snode pool is empty"
        }

        val response = snodeApiExecutor.get().execute(
            SnodeApiRequest(
                snode = pool.random(),
                api = getSwarmFactory.create(publicKey)
            )
        )

        return response.snodes
            .mapNotNull { it.toSnode() }
    }

    /**
     * Picks one snode from the user's swarm for a given account.
     * We deliberately randomise to avoid hammering a single node.
     */
    suspend fun getSingleTargetSnode(publicKey: String): Snode {
        val swarm = getSwarm(publicKey)
        require(swarm.isNotEmpty()) { "Swarm is empty for pubkey=$publicKey" }
        return swarm.shuffledRandom().random()
    }

    fun dropSnodeFromSwarmIfNeeded(snode: Snode, swarmPublicKey: String) {
        val current = storage.getSwarm(swarmPublicKey)
        if (snode !in current) return

        val updated = current - snode
        storage.setSwarm(swarmPublicKey, updated)
    }

    /**
     * Handles 421: snode says it's no longer associated with this pubkey.
     *
     * Old behaviour: if response contains snodes -> replace cached swarm.
     * Otherwise invalidate (caller may also drop the target snode from cached swarm).
     *
     * @return true if swarm was updated from body JSON, false otherwise.
     */
    fun updateSwarmFromResponse(swarmPublicKey: String, errorResponseBody: String?): Boolean {
        if (errorResponseBody == null || errorResponseBody.isEmpty()) return false

        val response: SnodeNotPartOfSwarmResponse = try {
            json.decodeFromString(errorResponseBody)
        } catch (e: Throwable) {
            Log.e("SwarmDirectory", "Failed to parse snode not part of swarm response body", e)
            return false
        }

        val snodes = response.snodes.mapNotNull {
            snodeDirectory.createSnode(
                address = it.ip,
                port = it.port,
                ed25519Key = it.pubKeyEd25519,
                x25519Key = it.pubKeyX25519
            )
        }

        if (snodes.isEmpty()) return false

        storage.setSwarm(swarmPublicKey, snodes)
        return true
    }

    @Serializable
    private class SnodeNotPartOfSwarmResponse(
        val snodes: List<SnodeInfo>
    ) {
        @Serializable
        class SnodeInfo(
            val ip: String? = null,
            val port: Int? = null,
            @SerialName("pubkey_ed25519")
            val pubKeyEd25519: String? = null,

            @SerialName("pubkey_x25519")
            val pubKeyX25519: String? = null
        )
    }
}
