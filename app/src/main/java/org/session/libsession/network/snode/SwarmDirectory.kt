package org.session.libsession.network.snode

import org.session.libsignal.crypto.shuffledRandom
import org.session.libsignal.utilities.JsonUtil
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
        val pool = snodeDirectory.getSnodePool()
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
     * Expected response shape:
     * { "snodes": [ { "ip": "...", "port": "443", "pubkey_ed25519": "...", "pubkey_x25519": "..." }, ... ] }
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSnodes(rawResponse: Map<*, *>): List<Snode> {
        val list = rawResponse["snodes"] as? List<*> ?: emptyList<Any>()
        return list.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { raw ->
                snodeDirectory.createSnode(
                    address    = raw["ip"] as? String,
                    port       = (raw["port"] as? String)?.toInt(),
                    ed25519Key = raw["pubkey_ed25519"] as? String,
                    x25519Key  = raw["pubkey_x25519"] as? String
                )
            }
            .toList()
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

        val json: Map<*, *> = try {
            JsonUtil.fromJson(errorResponseBody, Map::class.java) as Map<*, *>
        } catch (_: Throwable) {
            return false
        }

        val snodes = parseSnodes(json).toSet()
        if (snodes.isEmpty()) return false

        storage.setSwarm(swarmPublicKey, snodes)
        return true
    }
}
