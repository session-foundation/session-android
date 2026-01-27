package org.session.libsession.network.snode

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.execute
import org.thoughtcrime.securesms.api.snode.ListSnodeApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.execute
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SnodeDirectory @Inject constructor(
    private val storage: SnodePoolStorage,
    private val prefs: TextSecurePreferences,
    private val sessionApiExecutor: Provider<SessionApiExecutor>,
    private val snodeAPiExecutor: Provider<SnodeApiExecutor>,
    private val listSnodeApi: Provider<ListSnodeApi>,
    @param:ManagerScope private val scope: CoroutineScope,
    @param:ApplicationContext private val appContext: Context,
    private val json: Json,
) : OnAppStartupComponent {

    companion object {
        private const val MINIMUM_SNODE_POOL_COUNT = 12
        private const val SEED_NODE_PORT = 4443

        private const val POOL_REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L // 2h

        private const val KEY_IP = "public_ip"
        private const val KEY_PORT = "storage_port"
        private const val KEY_X25519 = "pubkey_x25519"
        private const val KEY_ED25519 = "pubkey_ed25519"

        private val DEV_NET_SEED_NODES = setOf(
            "http://sesh-net.local:1280".toHttpUrl()
        )

        private val TEST_NET_SEED_NODES = setOf(
            "http://public.loki.foundation:38157".toHttpUrl()
        )

        private val MAIN_NET_SEED_NODES = setOf(
            "https://seed1.getsession.org:$SEED_NODE_PORT".toHttpUrl(),
            "https://seed2.getsession.org:$SEED_NODE_PORT".toHttpUrl(),
            "https://seed3.getsession.org:$SEED_NODE_PORT".toHttpUrl()
        )

        private const val LOCAL_SNODE_POOL_ASSET = "snodes/snode_pool.json"
    }

    /**
     * Single mutex for any operation that can persist/replace the pool (bootstrap OR refresh).
     * This prevents refresh/bootstrap races overwriting each other.
     */
    private val poolWriteMutex = Mutex()

    // Refresh state (non-blocking trigger + real exclusion inside mutex)
    @Volatile private var snodePoolRefreshing = false

    val seedNodePool: Set<HttpUrl> get() = when (prefs.getEnvironment()) {
        Environment.DEV_NET -> DEV_NET_SEED_NODES
        Environment.TEST_NET -> TEST_NET_SEED_NODES
        Environment.MAIN_NET -> MAIN_NET_SEED_NODES
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
        //todo ONION in the new db paradigm we will need to ensure our path doesn't have snode not in this pool << Fanchao
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
            val result = runCatching {
                val api = listSnodeApi.get()
                val ctx = ApiExecutorContext()
                val request = api.buildRequest(ctx)
                val resp = sessionApiExecutor.get().execute(
                    ctx = ctx,
                    req = SessionApiRequest.SeedNodeJsonRPC(
                        seedNodeUrl = target,
                        request = request
                    )
                )

                api.handleResponse(
                    ctx = ctx,
                    snode = Snode(target, null),
                    code = resp.code,
                    body = resp.bodyAsJson
                )

            }.onFailure { e ->
                lastError = e
                Log.w("SnodeDirectory", "Seed node failed: $target", e)
            }
            .getOrNull()
            ?.toSnodeSet()

            if (!result.isNullOrEmpty()) return result
        }

        // All seeds failed -> local fallback
        Log.w("SnodeDirectory", "All seed nodes failed; falling back to local snode pool file.", lastError)

        @Suppress("OPT_IN_USAGE")
        val parsed: ListSnodeApi.Response = appContext.assets.open(LOCAL_SNODE_POOL_ASSET)
            .use(json::decodeFromStream)

        val nodes = parsed.toSnodeSet()

        if (nodes.isEmpty()) {
            throw IllegalStateException("Local snode pool file parsed empty", lastError)
        }

        Log.w("SnodeDirectory", "Successfully parsed snodes from local file.")
        return nodes
    }

    private suspend fun fetchSnodePoolFromSnode(snode: Snode): Set<Snode> {
        //TODO: Onion should request over onion
        return snodeAPiExecutor.get()
            .execute(SnodeApiRequest(snode, listSnodeApi.get()))
            .toSnodeSet()
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
