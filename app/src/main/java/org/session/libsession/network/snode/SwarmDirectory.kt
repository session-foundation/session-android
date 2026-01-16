package org.session.libsession.network.snode

import androidx.collection.arraySetOf
import dagger.Lazy
import org.session.libsession.network.SnodeClient
import org.session.libsignal.crypto.shuffledRandom
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.rpc.storage.GetSwarmRequest
import org.thoughtcrime.securesms.rpc.storage.StorageSnodeRPCExecutor
import org.thoughtcrime.securesms.rpc.storage.execute
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SwarmDirectory @Inject constructor(
    private val storage: SwarmStorage,
    private val snodeDirectory: SnodeDirectory,
    private val snodeClient: Lazy<SnodeClient>,
    private val snodeRPCExecutor: Provider<StorageSnodeRPCExecutor>,
    private val getSwarmRequestFactory: GetSwarmRequest.Factory,
) {
    private val minimumSwarmSize: Int = 3

    suspend fun getSwarm(publicKey: String): Set<Snode> {
        val cached = storage.getSwarm(publicKey)
        if (cached.size >= minimumSwarmSize) {
            return cached
        }

        val fresh = fetchSwarm(publicKey)
        storage.setSwarm(publicKey, fresh)
        return fresh
    }

    suspend fun fetchSwarm(publicKey: String): Set<Snode> {
        val pool = snodeDirectory.getSnodePool()
        require(pool.isNotEmpty()) {
            "Snode pool is empty"
        }

        val response = snodeRPCExecutor.get().execute(
            dest = pool.random(),
            req = getSwarmRequestFactory.create(publicKey)
        )

        return response.snodes
            .mapNotNullTo(arraySetOf()) { it.toSnode() }
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

    fun dropSnodeFromSwarmIfNeeded(snode: Snode, publicKey: String) {
        val current = storage.getSwarm(publicKey)
        if (snode !in current) return

        val updated = current - snode
        storage.setSwarm(publicKey, updated)
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
    fun updateSwarmFromResponse(publicKey: String, body: ByteArraySlice?): Boolean {
        if (body == null || body.isEmpty()) return false

        val json: Map<*, *> = try {
            JsonUtil.fromJson(body, Map::class.java) as Map<*, *>
        } catch (_: Throwable) {
            return false
        }

        val snodes = parseSnodes(json).toSet()
        if (snodes.isEmpty()) return false

        storage.setSwarm(publicKey, snodes)
        return true
    }
}
