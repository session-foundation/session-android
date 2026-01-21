package org.session.libsession.network.snode

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.session.libsignal.utilities.prettifiedDescription
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnodeDirectory @Inject constructor(
    private val storage: SnodePoolStorage,
    private val prefs: TextSecurePreferences,
    @ManagerScope private val scope: CoroutineScope,
    @ApplicationContext private val appContext: Context,
) : OnAppStartupComponent {

    companion object {
        private const val MINIMUM_SNODE_POOL_COUNT = 12
        private const val SEED_NODE_PORT = 4443

        private const val POOL_REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L // 2h

        private const val KEY_IP = "public_ip"
        private const val KEY_PORT = "storage_port"
        private const val KEY_X25519 = "pubkey_x25519"
        private const val KEY_ED25519 = "pubkey_ed25519"

        private const val LOCAL_SNODE_POOL_ASSET = "snodes/snode_pool.json"
    }

    /**
     * Single mutex for any operation that can persist/replace the pool (bootstrap OR refresh).
     * This prevents refresh/bootstrap races overwriting each other.
     */
    private val poolWriteMutex = Mutex()

    // Refresh state (non-blocking trigger + real exclusion inside mutex)
    @Volatile private var snodePoolRefreshing = false

    private val seedNodePool: Set<String> = when (prefs.getEnvironment()) {
        Environment.DEV_NET -> setOf("http://sesh-net.local:1280")
        Environment.TEST_NET -> setOf("http://public.loki.foundation:38157")
        Environment.MAIN_NET -> setOf(
            "https://seed1sdsdds.getsession.org:$SEED_NODE_PORT",
            "https://seed2sdfsdf.getsession.org:$SEED_NODE_PORT",
            "https://seed3sd.sdfsdfgetsession.org:$SEED_NODE_PORT",
        )
    }

    private val getRandomSnodeParams: Map<String, Any> = buildMap {
        this["method"] = "get_n_service_nodes"
        this["params"] = buildMap {
            this["active_only"] = true
            this["fields"] = sequenceOf(KEY_IP, KEY_PORT, KEY_X25519, KEY_ED25519)
                .associateWith { true }
        }
    }

    override fun onPostAppStarted() {
        // Ensure we have a populated snode pool on launch
        scope.launch {
            try {
                ensurePoolPopulated()
                Log.d("SnodeDirectory", "Snode pool populated on startup.")
            } catch (e: Exception) {
                Log.e("SnodeDirectory", "Failed to populate snode pool on startup", e)
            }
        }
    }

    fun getSnodePool(): Set<Snode> = storage.getSnodePool()

    private fun persistSnodePool(newPool: Set<Snode>) {
        //todo ONION in the new db paradigm we will need to ensure our path doesn't have snode not in this pool
        storage.setSnodePool(newPool)
        prefs.setLastSnodePoolRefresh(System.currentTimeMillis())
    }

    /**
     * Ensure the snode pool is populated to at least [minCount] elements.
     *
     * - If the current pool is already large enough, returns it unchanged.
     * - Otherwise, bootstraps from a random seed node (get_n_service_nodes),
     * persists the new pool, and returns it.
     *
     * Throws if the seed node returns an empty list or parsing fails.
     * Thread-safe: Ensures only one network call happens at a time.
     */
    suspend fun ensurePoolPopulated(
        minCount: Int = MINIMUM_SNODE_POOL_COUNT
    ): Set<Snode> {
        val current = getSnodePool()

        if (current.size >= minCount) {
            // ensure we set the refresh timestamp in case we are starting the app
            // with already cached snodes - set the timestamp to stale to enforce a refresh soon
            if (prefs.getLastSnodePoolRefresh() == 0L) {
                // Force a refresh on next opportunity
                prefs.setLastSnodePoolRefresh(System.currentTimeMillis() - POOL_REFRESH_INTERVAL_MS - 1)
            }

            return current
        }

        return poolWriteMutex.withLock {
            val freshCurrent = getSnodePool()
            if (freshCurrent.size >= minCount) return@withLock freshCurrent

            val seeded = fetchSnodePoolFromSeedWithFallback()
            if (seeded.isEmpty()) throw IllegalStateException("Seed node returned empty snode pool")

            Log.d("SnodeDirectory", "Persisting snode pool with ${seeded.size} snodes (seed bootstrap).")
            persistSnodePool(seeded)
            seeded
        }
    }

    private suspend fun fetchSnodePoolFromSeedWithFallback(): Set<Snode> {
        val seeds = seedNodePool.shuffled()

        var lastError: Throwable? = null

        for (target in seeds) {
            Log.d("SnodeDirectory", "Fetching snode pool using seed node: $target")
            val result = runCatching { fetchSnodePool(target, fromSeed = true) }
                .onFailure { e ->
                    lastError = e
                    Log.w("SnodeDirectory", "Seed node failed: $target", e)
                }
                .getOrNull()

            if (!result.isNullOrEmpty()) return result
        }

        // All seeds failed -> local fallback
        Log.w("SnodeDirectory", "All seed nodes failed; falling back to local snode pool file.", lastError)

        val localBytes = readLocalSnodePoolJsonBytes()
        val parsed = parseSnodePoolFromJsonBytes(localBytes)

        if (parsed.isEmpty()) {
            throw IllegalStateException("Local snode pool file parsed empty", lastError)
        }

        Log.w("SnodeDirectory", "Successfully parsed snodes from local file.")
        return parsed
    }

    private suspend fun fetchSnodePool(target: String, fromSeed: Boolean): Set<Snode> {
        val url = "$target/json_rpc"
        val responseBytes = HTTP.execute(
            HTTP.Verb.POST,
            url = url,
            parameters = getRandomSnodeParams,
            useSeedNodeConnection = fromSeed
        )
        return parseSnodePoolFromJsonBytes(responseBytes)
    }

    /**
     * Reads the bundled local snode pool JSON.
     * Throws if missing/unreadable.
     */
    private fun readLocalSnodePoolJsonBytes(): ByteArray {
        return appContext.assets.open(LOCAL_SNODE_POOL_ASSET).use { it.readBytes() }
    }

    /**
     * Parse the JSON-RPC response format into a Set<Snode>.
     */
    private fun parseSnodePoolFromJsonBytes(responseBytes: ByteArray): Set<Snode> {
        val json = runCatching {
            JsonUtil.fromJson(responseBytes, Map::class.java)
        }.getOrNull() ?: buildMap<String, Any> {
            this["result"] = responseBytes.toString(Charsets.UTF_8)
        }

        @Suppress("UNCHECKED_CAST")
        val intermediate = json["result"] as? Map<*, *>
            ?: throw IllegalStateException("Failed to parse snode pool, 'result' was null.")
                .also { Log.d("SnodeDirectory", "Failed to parse snode pool, intermediate was null.") }

        @Suppress("UNCHECKED_CAST")
        val rawSnodes = intermediate["service_node_states"] as? List<*>
            ?: throw IllegalStateException("Failed to parse snode pool, 'service_node_states' was null.")
                .also { Log.d("SnodeDirectory", "Failed to parse snode pool, rawSnodes was null.") }

        return rawSnodes.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { raw ->
                createSnode(
                    address = raw[KEY_IP] as? String,
                    port = raw[KEY_PORT] as? Int,
                    ed25519Key = raw[KEY_ED25519] as? String,
                    x25519Key = raw[KEY_X25519] as? String,
                ).also {
                    if (it == null) {
                        Log.d("SnodeDirectory", "Failed to parse snode from: ${raw.prettifiedDescription()}.")
                    }
                }
            }
            .toSet()
    }

    private suspend fun fetchSnodePoolFromSnode(snode: Snode): Set<Snode> {
        val target = "${snode.address}:${snode.port}"
        Log.d("SnodeDirectory", "Fetching snode pool using snode: $target")
        //todo ONION use fetchSnodePoolCompact when ready
        return fetchSnodePool(target, fromSeed = false)
    }

    /**
     * Calling the compact version of the snode pool api
     */
    private suspend fun fetchSnodePoolCompact(snode: Snode): Set<Snode>{
        //todo ONION link up with new logic
        return emptySet()
    }


    /**
     * Returns a random snode from the generic snode pool.
     *
     * Uses [ensurePoolPopulated] under the hood, so you still get lazy bootstrap if
     * startup population failed or hasn’t run yet.
     */
    suspend fun getRandomSnode(): Snode {
        val pool = ensurePoolPopulated()
        return pool.secureRandom()
    }

    fun createSnode(
        address: String?,
        port: Int?,
        ed25519Key: String?,
        x25519Key: String?,
    ): Snode? {
        return Snode(
            address?.takeUnless { it == "0.0.0.0" }?.let { "https://$it" } ?: return null,
            port ?: return null,
            Snode.KeySet(ed25519Key ?: return null, x25519Key ?: return null),
        )
    }

    suspend fun getGuardSnodes(
        existingGuards: Set<Snode>,
        targetGuardCount: Int
    ): Set<Snode> {
        if (existingGuards.size >= targetGuardCount) return existingGuards

        var unused = ensurePoolPopulated().minus(existingGuards)
        val needed = targetGuardCount - existingGuards.size

        if (unused.size < needed) {
            throw IllegalStateException("Insufficient snodes to build guards")
        }

        val newGuards = (0 until needed).map {
            val candidate = unused.secureRandom()
            unused = unused - candidate
            Log.d("Onion Request", "Selected guard snode: $candidate")
            candidate
        }

        return (existingGuards + newGuards).toSet()
    }

    /**
     * Remove a snode from the pool by its ed25519 key.
     */
    fun dropSnodeFromPool(ed25519Key: String) {
        val removed = storage.removeSnode(ed25519Key)
        Log.w("SnodeDirectory", "Dropping snode from pool (ed25519=$ed25519Key): ${removed != null}")

        // NOTE: do NOT touch lastRefreshElapsedMs here; dropping isn’t a “refresh”.
    }

    fun getSnodeByKey(ed25519Key: String?): Snode? {
        if (ed25519Key == null) return null
        return getSnodePool().firstOrNull { it.publicKeySet?.ed25519Key == ed25519Key }
    }

    // snode pool refresh logic

    /**
     * Non-blocking trigger.
     *
     * IMPORTANT: does nothing until we have successfully seeded at least once
     * (lastRefreshElapsedMs != 0L).
     */
    fun refreshPoolIfStaleAsync() {
        // Don’t refresh until we’ve successfully seeded at least once
        val last = prefs.getLastSnodePoolRefresh()
        if (last == 0L) return

        val now = System.currentTimeMillis()
        if (snodePoolRefreshing) return
        if (now >= last && now - last < POOL_REFRESH_INTERVAL_MS) return

        scope.launch {
            refreshPoolFromSnodes(
                totalSnodeQueries = 3,
                minAppearance = 2,
                distinctQuerySubnetPrefix = 24,
            )
        }
    }

    private suspend fun refreshPoolFromSnodes(
        totalSnodeQueries: Int,
        minAppearance: Int,
        distinctQuerySubnetPrefix: Int?,
    ) {
        require(totalSnodeQueries >= 1) { "totalSnodeQueries must be >= 1" }
        require(minAppearance in 1..totalSnodeQueries) { "minAppearance must be within 1..totalSnodeQueries" }

        poolWriteMutex.withLock {
            // Re-check staleness INSIDE the lock to avoid “double refresh” races
            val last = prefs.getLastSnodePoolRefresh()
            if (last == 0L) return// still not seeded
            val now = System.currentTimeMillis()
            if (now >= last && now - last < POOL_REFRESH_INTERVAL_MS) return

            if (snodePoolRefreshing) return
            snodePoolRefreshing = true

            try {
                val current = getSnodePool()

                suspend fun getFromSeed(msg: String){
                    val seeded = fetchSnodePoolFromSeedWithFallback()
                    if (seeded.isNotEmpty()) {
                        Log.d("SnodeDirectory", "$msg New size=${seeded.size}")
                        persistSnodePool(seeded)
                    }
                }

                // If pool is too small, refresh from seed
                if (current.size < totalSnodeQueries) {
                    getFromSeed("Refreshing pool from seed (pool too small)")
                    return
                }

                // Choose snodes to query pool from
                val snodesToQuery = pickViableSnodesForPoolQuery(
                    pool = current,
                    count = totalSnodeQueries,
                    distinctSubnetPrefix = distinctQuerySubnetPrefix
                )

                // If we don't have enough snodes meeting our requirements, fallback to seed
                if (snodesToQuery.size < totalSnodeQueries) {
                    getFromSeed("Not enough snodes meeting our requirements; refreshing pool from seed instead.")
                    return
                }

                // Fetch pools from responders until we have totalSnodeQueries successful results
                val results = mutableListOf<Set<Snode>>()

                for (snode in snodesToQuery) {
                    if (results.size >= totalSnodeQueries) break
                    val fetched = runCatching { fetchSnodePoolFromSnode(snode) }.getOrNull()
                    if (!fetched.isNullOrEmpty()) results += fetched
                }

                if (results.size < totalSnodeQueries) {
                    getFromSeed( "Refreshing pool from seed (insufficient responder fetches).")
                    return
                }

                val quorum = quorumByEd25519(pools = results, minAppearance = minAppearance)

                if (quorum.isEmpty()) {
                    getFromSeed("Quorum empty; refreshing pool from seed instead.")
                    return
                }
                if (quorum.size < MINIMUM_SNODE_POOL_COUNT) {
                    getFromSeed("Quorum too small (${quorum.size}); refreshing pool from seed instead.")
                    return
                }

                Log.d(
                    "SnodeDirectory",
                    "Refreshing pool via quorum (minAppearance=$minAppearance of $totalSnodeQueries, distinctSubnetPrefix=$distinctQuerySubnetPrefix). New size=${quorum.size}"
                )
                persistSnodePool(quorum)

            } finally {
                snodePoolRefreshing = false
            }
        }
    }

    private fun pickViableSnodesForPoolQuery(
        pool: Set<Snode>,
        count: Int,
        distinctSubnetPrefix: Int?
    ): List<Snode> {
        if (pool.isEmpty() || count <= 0) return emptyList()

        val shuffled = pool.shuffled()

        // If subnet diversification disabled, just take first N
        if (distinctSubnetPrefix == null) {
            return shuffled.take(count)
        }

        // Enforce distinct subnet prefix where possible
        val picked = ArrayList<Snode>(count)
        val used = mutableSetOf<String>()
        for (snode in shuffled) {
            if (picked.size >= count) break
            val subnet = subnetPrefixOrNull(snode.ip, distinctSubnetPrefix) ?: continue
            if (!used.add(subnet)) continue
            picked += snode
        }

        return picked
    }

    /**
     * Returns a subnet prefix string like:
     * - prefix=24 -> "a.b.c"
     * - prefix=16 -> "a.b"
     * - prefix=8  -> "a"
     *
     * Returns null if prefix is null/unsupported or IP isn't IPv4.
     */
    private fun subnetPrefixOrNull(ip: String, prefix: Int?): String? {
        if (prefix == null) return null
        val octetsToKeep = when (prefix) {
            8 -> 1
            16 -> 2
            24 -> 3
            else -> return null
        }

        val parts = ip.split('.')
        if (parts.size != 4) return null

        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null

        return octets.take(octetsToKeep).joinToString(".")
    }

    private fun quorumByEd25519(
        pools: List<Set<Snode>>,
        minAppearance: Int
    ): Set<Snode> {
        if (pools.isEmpty()) return emptySet()

        val voteCount = mutableMapOf<String, Int>()
        val bestByKey = mutableMapOf<String, Snode>()

        for (pool in pools) {
            // using this in spite of Set in case we get bad data with snodes that use the same key with diff address/port
            val seenThisPool = mutableSetOf<String>()

            for (snode in pool) {
                val key = snode.publicKeySet?.ed25519Key ?: continue
                if (!seenThisPool.add(key)) continue  // <-- important

                voteCount[key] = (voteCount[key] ?: 0) + 1

                val currentBest = bestByKey[key]
                bestByKey[key] = when {
                    currentBest == null -> snode
                    else -> currentBest
                }
            }
        }

        return voteCount.asSequence()
            .filter { (_, count) -> count >= minAppearance }
            .mapNotNull { (key, _) -> bestByKey[key] }
            .toSet()
    }
}
