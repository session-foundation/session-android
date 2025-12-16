package org.session.libsession.network.snode

import org.session.libsession.network.SessionNetwork
import org.session.libsession.network.onion.Version
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Snode

class SwarmDirectory(
    private val storage: SwarmStorage,
    private val snodeDirectory: SnodeDirectory,
    private val sessionNetwork: SessionNetwork,
    private val minimumSwarmSize: Int = 3
) {

    suspend fun getSwarm(publicKey: String): Set<Snode> {
        val cached = storage.getSwarm(publicKey)
        if (cached != null && cached.size >= minimumSwarmSize) {
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

        val randomSnode = pool.random()

        val params = mapOf("pubKey" to publicKey)

        val result = sessionNetwork.sendToSnode(
            method   = Snode.Method.GetSwarm,
            parameters   = params,
            snode    = randomSnode,
            version  = Version.V4
        )

        if (result.isFailure) {
            throw result.exceptionOrNull() ?: IllegalStateException("Unknown swarm error")
        }

        val onionResponse = result.getOrThrow()
        val body = onionResponse.body ?: error("Empty GetSwarm body")
        val json = JsonUtil.fromJson(body, Map::class.java) as Map<*, *>

        return parseSnodes(json).toSet()
    }

    fun dropSnodeFromSwarmIfNeeded(snode: Snode, publicKey: String) {
        val current = storage.getSwarm(publicKey) ?: return
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
            JsonUtil.fromJson(body.copyToBytes(), Map::class.java) as Map<*, *>
        } catch (_: Throwable) {
            return false
        }

        val snodes = parseSnodes(json).toSet()
        if (snodes.isEmpty()) return false

        storage.setSwarm(publicKey, snodes)
        return true
    }
}
