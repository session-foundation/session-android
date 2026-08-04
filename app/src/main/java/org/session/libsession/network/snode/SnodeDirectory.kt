package org.session.libsession.network.snode

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.network.onion.PathManager
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.crypto.shuffledSequence
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.SessionApiExecutor
import org.thoughtcrime.securesms.api.SessionApiRequest
import org.thoughtcrime.securesms.api.execute
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.snode.ListSnodeApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.SnodeJsonRequest
import org.thoughtcrime.securesms.api.snode.execute
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SnodeDirectory @Inject constructor(
    private val storage: SnodePoolStorage,
    private val prefs: TextSecurePreferences,
    private val httpExecutor: Provider<HttpApiExecutor>,
    private val snodeAPiExecutor: Provider<SnodeApiExecutor>,
    private val listSnodeApi: Provider<ListSnodeApi>,
    private val pathManager: Provider<PathManager>,
    @param:ManagerScope private val scope: CoroutineScope,
    @param:ApplicationContext private val appContext: Context,
    private val json: Json,
) : OnAppStartupComponent {

    companion object {
        private const val MINIMUM_SNODE_POOL_COUNT = 12
        private const val SEED_NODE_PORT = 4443

        private const val POOL_REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L // 2h


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
        Environment.DEV_NET -> devNetSeedNodes()
        Environment.TEST_NET -> TEST_NET_SEED_NODES
        Environment.MAIN_NET -> MAIN_NET_SEED_NODES
    }

    /**
     * The devnet seed node, honouring the [TextSecurePreferences.getDevnetSeedUrl] override so a
     * devnet can be targeted without rebuilding the app (see QaLaunchConfig). Falls back to the
     * built-in constant when unset, so existing devnet builds are unaffected.
     *
     * A malformed override is logged and ignored rather than throwing: this runs on the startup path,
     * and a bad value should degrade to the default rather than prevent the app from launching.
     */
    private fun devNetSeedNodes(): Set<HttpUrl> {
        val override = prefs.getDevnetSeedUrl()?.takeIf { it.isNotBlank() } ?: return DEV_NET_SEED_NODES

        val parsed = override.toHttpUrlOrNull()
        if (parsed == null) {
            Log.e("SnodeDirectory", "Ignoring malformed devnet seed URL override: '$override'")
            return DEV_NET_SEED_NODES
        }

        Log.i("SnodeDirectory", "Using devnet seed URL override: $parsed")
        return setOf(parsed)
    }

    /**
     * Identifies the seed configuration a pool was fetched under: the environment plus the resolved
     * seed URLs, so it covers the debug menu, the devnet seed override, and any future change to the
     * seed lists. Compared as an opaque string; nothing parses it back.
     */
    private fun currentSeedMarker(): String = buildString {
        append(prefs.getEnvironment().name)
        append('|')
        append(seedNodePool.map(HttpUrl::toString).sorted().joinToString(","))
    }

    /**
     * Drops everything derived from a DIFFERENT seed configuration than the one now in effect.
     *
     * Without this, [ensurePoolPopulated] happily returns a cached pool belonging to the previous
     * network — so changing the environment or the devnet seed URL appears to do nothing until the
     * app's data is wiped. That is why the debug menu's environment switch wipes everything; this
     * makes the far more common case (config changed, caches stale) self-correcting instead.
     *
     * Everything network-derived hangs off the snode pool, so clearing it covers the lot: the stored
     * onion paths and swarm memberships are removed with it (they are FK-bound to the snodes table),
     * and the in-memory copies are dropped alongside. What is deliberately NOT touched is the
     * account: keys, config and messages are the user's, not the network's, and a QA run that wants
     * them gone reinstalls.
     */
    private suspend fun discardPoolIfSeedChanged() {
        val marker = currentSeedMarker()

        val previous = prefs.getSnodePoolSeedMarker()
        if (previous == marker) {
            return
        }

        // Absent marker means a pool from before this bookkeeping existed (or a fresh install with an
        // empty pool). Record the marker either way; only actually discard when we know it changed.
        if (previous != null) {
            Log.i(
                "SnodeDirectory",
                "Seed configuration changed ($previous -> $marker); discarding cached snode pool"
            )

            // Also drops the stored onion paths, and the swarms, since both are made of pool members
            // and every FK points back at the snodes table.
            storage.setSnodePool(emptyList())
            prefs.setLastSnodePoolRefresh(0L)

            // The rows are gone, but PathManager holds its own in-memory copy of the paths and would
            // keep routing over the previous network's snodes until enough failures accumulated to
            // force a rebuild. Injected as a Provider because PathManager depends on us.
            pathManager.get().clearPaths()
        }

        prefs.setSnodePoolSeedMarker(marker)
    }

    /**
     * Runs the [discardPoolIfSeedChanged] check off the caller's thread.
     *
     * Needed because that check is otherwise only reachable from [ensurePoolPopulated], which is not
     * called at all on a launch that already has a usable pool and cached paths — there is nothing to
     * populate. A configuration change made after startup (QaLaunchConfig can only read the launch
     * extras once the first activity exists) would then sit there doing nothing: the app would keep
     * routing over the previous network until the pool happened to need repopulating, which may not
     * happen for hours. Verified on device: without this, switching to devnet left a 1031-snode
     * mainnet pool and its paths in place for the whole session.
     */
    fun discardPoolIfSeedChangedAsync() {
        scope.launch {
            try {
                discardPoolIfSeedChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SnodeDirectory", "Failed to apply seed configuration change", e)
            }
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

    fun getSnodePool(): List<Snode> = storage.getSnodePool()

    /**
     * Persists [newPool] unless the seed configuration moved while it was being fetched.
     *
     * [expectedSeedMarker] is [currentSeedMarker] as of the moment the fetch started. Note that
     * [discardPoolIfSeedChanged] deliberately runs OUTSIDE [poolWriteMutex] — it has to, because
     * a snode fetch performed under that mutex reaches back into [ensurePoolPopulated] via the
     * onion path builder, and a non-reentrant mutex would deadlock. So a discard can land between
     * a fetch starting and this persist; without this check the fetched (old-network) pool would be
     * written behind an ALREADY-UPDATED marker, and [discardPoolIfSeedChanged] would never clean it
     * up again — leaving the app permanently pinned to the previous network. Dropping the fetch
     * instead costs one refresh cycle.
     */
    private fun persistSnodePool(newPool: List<Snode>, expectedSeedMarker: String) {
        val marker = currentSeedMarker()
        if (marker != expectedSeedMarker) {
            Log.w(
                "SnodeDirectory",
                "Seed configuration changed while fetching ($expectedSeedMarker -> $marker); dropping fetched pool"
            )
            return
        }

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
    ): List<Snode> {
        discardPoolIfSeedChanged()

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

            val marker = currentSeedMarker()
            val seeded = fetchSnodePoolFromSeedWithFallback()
            if (seeded.isEmpty()) throw IllegalStateException("Seed node returned empty snode pool")

            Log.d("SnodeDirectory", "Persisting snode pool with ${seeded.size} snodes (seed bootstrap).")
            persistSnodePool(seeded, marker)
            seeded
        }
    }

    private suspend fun fetchSnodePoolFromSeedWithFallback(): List<Snode> {
        val seeds = seedNodePool.shuffled()

        var lastError: Throwable? = null

        for (target in seeds) {
            Log.d("SnodeDirectory", "Fetching snode pool using seed node: $target")
            @Suppress("OPT_IN_USAGE") val result = runCatching {
                val body = httpExecutor.get().send(
                    ctx = ApiExecutorContext(),
                    req = HttpRequest.createFromJson(
                        url = target.resolve("/json_rpc")!!,
                        method = "POST",
                        jsonText = json.encodeToString(
                            SnodeJsonRequest(
                                method = "get_n_service_nodes",
                                params = ListSnodeApi.buildRequestJson()
                            )
                        )
                    )
                ).throwIfNotSuccessful().body

                body
                    .asInputStream()
                    .use {
                        json.decodeFromStream<SeedNodeSnodeFetchResult>(it)
                    }
                    .result
            }.onFailure { e ->
                if (e is CancellationException) throw e

                lastError = e
                Log.w("SnodeDirectory", "Seed node failed: $target", e)
            }
            .getOrNull()
            ?.toSnodeList()

            if (!result.isNullOrEmpty()) return result
        }

        // All seeds failed -> local fallback
        Log.w("SnodeDirectory", "All seed nodes failed; falling back to local snode pool file.", lastError)

        @Suppress("OPT_IN_USAGE")
        val parsed: SeedNodeSnodeFetchResult = appContext.assets.open(LOCAL_SNODE_POOL_ASSET)
            .use(json::decodeFromStream)

        val nodes = parsed.result.toSnodeList()

        if (nodes.isEmpty()) {
            throw IllegalStateException("Local snode pool file parsed empty", lastError)
        }

        Log.w("SnodeDirectory", "Successfully parsed snodes from local file.")
        return nodes
    }

    @Serializable
    private class SeedNodeSnodeFetchResult(
        val result: ListSnodeApi.Response,
    )

    private suspend fun fetchSnodePoolFromSnode(snode: Snode): List<Snode> {
        return snodeAPiExecutor.get()
            .execute(SnodeApiRequest(snode, listSnodeApi.get()))
            .toSnodeList()
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
            try {
                refreshPoolFromSnodes(
                    totalSnodeQueries = 3,
                    minAppearance = 2,
                    distinctQuerySubnetPrefix = 24,
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e

                Log.w("SnodeDirectory", "Error refreshing snode pool", e)
            }
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
                val marker = currentSeedMarker()

                suspend fun getFromSeed(msg: String){
                    val seeded = fetchSnodePoolFromSeedWithFallback()
                    if (seeded.isNotEmpty()) {
                        Log.d("SnodeDirectory", "$msg New size=${seeded.size}")
                        persistSnodePool(seeded, marker)
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
                val results = mutableListOf<List<Snode>>()

                for (snode in snodesToQuery) {
                    if (results.size >= totalSnodeQueries) break
                    val fetched = runCatching { fetchSnodePoolFromSnode(snode) }
                        .onFailure { Log.w("SnodeDirectory", "Error fetching snode pool", it) }
                        .getOrNull()
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
                persistSnodePool(quorum, marker)

            } finally {
                snodePoolRefreshing = false
            }
        }
    }

    private fun pickViableSnodesForPoolQuery(
        pool: List<Snode>,
        count: Int,
        distinctSubnetPrefix: Int?
    ): List<Snode> {
        if (pool.isEmpty() || count <= 0) return emptyList()

        // If subnet diversification disabled, just take first N
        if (distinctSubnetPrefix == null) {
            return pool
                .shuffledSequence()
                .take(count)
                .toList()
        } else {
            // Enforce distinct subnet prefix where possible
            val picked = ArrayList<Snode>(count)
            val used = mutableSetOf<String>()
            for (snode in pool.shuffledSequence()) {
                if (picked.size >= count) break
                val subnet = subnetPrefixOrNull(snode.ip, distinctSubnetPrefix) ?: continue
                if (!used.add(subnet)) continue
                picked += snode
            }

            return picked
        }
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
        pools: List<List<Snode>>,
        minAppearance: Int
    ): List<Snode> {
        if (pools.isEmpty()) return emptyList()

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
            .toList()
    }
}
