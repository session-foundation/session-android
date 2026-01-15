package org.session.libsession.network.snode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.ForkInfo
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
) : OnAppStartupComponent {

    companion object {
        private const val MINIMUM_SNODE_POOL_COUNT = 12
        private const val MINIMUM_SNODE_REFRESH_COUNT = 3
        private const val SEED_NODE_PORT = 4443

        private const val POOL_REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L // 2h

        private const val KEY_IP = "public_ip"
        private const val KEY_PORT = "storage_port"
        private const val KEY_X25519 = "pubkey_x25519"
        private const val KEY_ED25519 = "pubkey_ed25519"
        private const val KEY_VERSION = "storage_server_version"
    }

    private val poolMutex = Mutex()

    // snode refresh properties
    private val refreshMutex = Mutex()
    @Volatile private var snodePoolRefreshing = false
    @Volatile private var lastRefreshMs: Long = 0L

    private val seedNodePool: Set<String> = when (prefs.getEnvironment()) {
        Environment.DEV_NET -> setOf("http://sesh-net.local:1280")
        Environment.TEST_NET -> setOf("http://public.loki.foundation:38157")
        Environment.MAIN_NET -> setOf(
            "https://seed1.getsession.org:$SEED_NODE_PORT",
            "https://seed2.getsession.org:$SEED_NODE_PORT",
            "https://seed3.getsession.org:$SEED_NODE_PORT",
        )
    }

    private val getRandomSnodeParams: Map<String, Any> = buildMap {
        this["method"] = "get_n_service_nodes"
        this["params"] = buildMap {
            this["active_only"] = true
            this["fields"] = sequenceOf(KEY_IP, KEY_PORT, KEY_X25519, KEY_ED25519, KEY_VERSION)
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

    fun updateSnodePool(newPool: Set<Snode>) {
        storage.setSnodePool(newPool)
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
        // 1. Fast path: Optimistic check (no lock)
        val current = getSnodePool()
        if (current.size >= minCount) {
            return current
        }

        // 2. Slow path: Acquire lock
        return poolMutex.withLock {
            // 3. Double-check: Did someone populate it while we were waiting?
            val freshCurrent = getSnodePool()
            if (freshCurrent.size >= minCount) {
                return@withLock freshCurrent
            }

            fetchSnodePoolFromSeed()
        }
    }

    private suspend fun fetchSnodePoolFromSeed(): Set<Snode> {
        val target = seedNodePool.random()
        Log.d("SnodeDirectory", "Populating snode pool using seed node: $target")

        return fetchSnodePool(target, fromSeed = true)
    }

    private suspend fun fetchSnodePoolFromSnode(snode: Snode): Set<Snode> {
        Log.d("SnodeDirectory", "Populating snode pool using snode: ${snode.address}:${snode.port}")

        return fetchSnodePool( "${snode.address}:${snode.port}", fromSeed = false)
    }

    private suspend fun fetchSnodePool(target: String, fromSeed: Boolean): Set<Snode> {
        val url = "$target/json_rpc"
        val responseBytes = HTTP.execute(
            HTTP.Verb.POST,
            url = url,
            parameters = getRandomSnodeParams,
            useSeedNodeConnection = fromSeed
        )

        val json = runCatching {
            JsonUtil.fromJson(responseBytes, Map::class.java)
        }.getOrNull() ?: buildMap<String, Any> {
            this["result"] = responseBytes.toString(Charsets.UTF_8)
        }

        @Suppress("UNCHECKED_CAST")
        val intermediate = json["result"] as? Map<*, *>
            ?: throw IllegalStateException("Failed to update snode pool, 'result' was null.")
                .also { Log.d("SnodeDirectory", "Failed to update snode pool, intermediate was null.") }

        @Suppress("UNCHECKED_CAST")
        val rawSnodes = intermediate["service_node_states"] as? List<*>
            ?: throw IllegalStateException("Failed to update snode pool, 'service_node_states' was null.")
                .also { Log.d("SnodeDirectory", "Failed to update snode pool, rawSnodes was null.") }

        val newPool = rawSnodes.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { raw ->
                createSnode(
                    address = raw[KEY_IP] as? String,
                    port = raw[KEY_PORT] as? Int,
                    ed25519Key = raw[KEY_ED25519] as? String,
                    x25519Key = raw[KEY_X25519] as? String,
                    version = (raw[KEY_VERSION] as? List<*>)
                        ?.filterIsInstance<Int>()
                        ?.let(Snode::Version)
                ).also {
                    if (it == null) {
                        Log.d(
                            "SnodeDirectory",
                            "Failed to parse snode from: ${raw.prettifiedDescription()}."
                        )
                    }
                }
            }
            .toSet()

        if (newPool.isEmpty()) {
            throw IllegalStateException("Seed node returned empty snode pool")
        }

        Log.d("SnodeDirectory", "Persisting snode pool with ${newPool.size} snodes.")
        updateSnodePool(newPool)

        return newPool
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
        version: Snode.Version? = Snode.Version.ZERO
    ): Snode? {
        return Snode(
            address?.takeUnless { it == "0.0.0.0" }?.let { "https://$it" } ?: return null,
            port ?: return null,
            Snode.KeySet(ed25519Key ?: return null, x25519Key ?: return null),
            version ?: return null
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
        val current = getSnodePool()
        val hit = current.firstOrNull { it.publicKeySet?.ed25519Key == ed25519Key } ?: return
        Log.w("SnodeDirectory", "Dropping snode from pool (ed25519=$ed25519Key): $hit")
        updateSnodePool(current - hit)
    }

    fun updateForkInfo(newForkInfo: ForkInfo) {
        val current = storage.getForkInfo()
        if (newForkInfo > current) {
            Log.d("Loki", "Updating fork info: $current -> $newForkInfo")
            storage.setForkInfo(newForkInfo)
        } else if (newForkInfo < current) {
            Log.w("Loki", "Got stale fork info $newForkInfo (current: $current)")
        }
    }

    fun getSnodeByKey(ed25519Key: String?): Snode?{
        if(ed25519Key == null) return null
        return getSnodePool().firstOrNull { it.publicKeySet?.ed25519Key == ed25519Key }
    }

    // snode pool refresh logic
    /**
     * Non blocking snode pool refresh
     */
    fun refreshPoolIfStaleAsync() {
        val now = System.currentTimeMillis()
        if (snodePoolRefreshing) return
        if (now - lastRefreshMs < POOL_REFRESH_INTERVAL_MS) return

        scope.launch {
            refreshPoolFromSnodes()
        }
    }

    private suspend fun refreshPoolFromSnodes() {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            snodePoolRefreshing = true
            try {
                val current = getSnodePool()

                // If pool has less than 3 snodes, refresh from seed
                if (current.size < MINIMUM_SNODE_REFRESH_COUNT) {
                    val seeded = fetchSnodePoolFromSeed()
                    updateSnodePool(seeded)
                    lastRefreshMs = now
                    return
                }

                // Otherwise fetch from 3 random snodes
                val results = mutableListOf<Set<Snode>>()
                val attempts = current.shuffled().iterator()

                while (results.size < MINIMUM_SNODE_REFRESH_COUNT && attempts.hasNext()) {
                    val snode = attempts.next()
                    val fetched = runCatching { fetchSnodePoolFromSnode(snode) }.getOrNull()
                    if (fetched != null && fetched.isNotEmpty()) results += fetched
                }

                if (results.size < MINIMUM_SNODE_REFRESH_COUNT) {
                    // Could not fetch 3 pools reliably, fallback to seed
                    val seeded = fetchSnodePoolFromSeed()
                    updateSnodePool(seeded)
                    lastRefreshMs = now
                    return
                }

                val intersected = intersectByEd25519(results)

                updateSnodePool(intersected)
                lastRefreshMs = now

            } finally {
                snodePoolRefreshing = false
            }
        }
    }

    private fun intersectByEd25519(pools: List<Set<Snode>>): Set<Snode> {
        if (pools.isEmpty()) return emptySet()

        // 1. Take the first pool as our baseline candidates
        val candidates = pools.first()

        // 2. Pre-calculate the key sets for the remaining pools for fast lookup
        val otherPoolKeys = pools.drop(1).map { pool ->
            pool.mapNotNull { it.publicKeySet?.ed25519Key }.toSet()
        }

        // 3. Filter candidates: keep only if the key exists in ALL other pools
        return candidates.filter { snode ->
            val key = snode.publicKeySet?.ed25519Key ?: return@filter false
            otherPoolKeys.all { it.contains(key) }
        }.toSet()
    }
}