package org.session.libsession.network.onion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.session.libsession.network.model.Path
import org.session.libsession.network.model.PathStatus
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathManager @Inject constructor(
    private val scope: CoroutineScope,
    private val directory: SnodeDirectory,
    private val storage: SnodePathStorage,
    private val swarmDirectory: SwarmDirectory,
) {
    companion object {
        private const val STRIKE_THRESHOLD = 3
    }

    private val pathSize: Int = 3
    private val targetPathCount: Int = 2

    private val _paths = MutableStateFlow(
        sanitizePaths(storage.getOnionRequestPaths())
    )
    val paths: StateFlow<List<Path>> = _paths.asStateFlow()

    // Used for synchronization
    private val buildMutex = Mutex()
    private val _isBuilding = MutableStateFlow(false)

    // In-memory strike tracking (same lifetime as PathManager)
    private val pathStrikes: MutableMap<String, Int> = mutableMapOf()
    private val snodeStrikes: MutableMap<String, Int> = mutableMapOf()

    private fun snodeKey(snode: Snode): String =
        snode.publicKeySet?.ed25519Key ?: snode.toString()

    private fun pathKey(path: Path): String =
        path.joinToString(separator = "|") { it.publicKeySet?.ed25519Key ?: it.toString() }

    private fun increasePathStrike(path: Path): Int {
        val key = pathKey(path)
        val next = (pathStrikes[key] ?: 0) + 1
        pathStrikes[key] = next
        return next
    }

    private fun increaseSnodeStrike(snode: Snode): Int {
        val key = snodeKey(snode)
        val next = (snodeStrikes[key] ?: 0) + 1
        snodeStrikes[key] = next
        return next
    }

    private fun setSnodeStrikes(snode: Snode, strikes: Int): Int {
        val key = snodeKey(snode)
        snodeStrikes[key] = strikes
        return strikes
    }

    private fun clearPathStrike(path: Path) {
        pathStrikes.remove(pathKey(path))
    }

    private fun clearSnodeStrike(snode: Snode) {
        snodeStrikes.remove(snodeKey(snode))
    }

    // -----------------------------
    // Flow Setup
    // -----------------------------

    @OptIn(FlowPreview::class)
    val status: StateFlow<PathStatus> =
        combine(_paths, _isBuilding) { paths, building ->
            when {
                building -> PathStatus.BUILDING
                paths.isEmpty() -> PathStatus.ERROR
                else -> PathStatus.READY
            }
        }
            .debounce(250)
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                if (_paths.value.isEmpty()) PathStatus.ERROR else PathStatus.READY
            )

    init {
        // persist to DB whenever paths change
        scope.launch {
            _paths.drop(1).collectLatest { paths ->
                if (paths.isEmpty()) storage.clearOnionRequestPaths()
                else storage.setOnionRequestPaths(paths)
            }
        }
    }

    // -----------------------------
    // Public API
    // -----------------------------

    suspend fun getPath(exclude: Snode? = null): Path {
        val current = _paths.value
        if (current.size >= targetPathCount && current.any { exclude == null || !it.contains(exclude) }) {
            return selectPath(current, exclude)
        }

        Log.w("Onion Request", "We only have ${current.size}/$targetPathCount paths, need to rebuild path.")

        // Wait for rebuild to finish if one is happening, or start one
        rebuildPaths(reusablePaths = current)

        val rebuilt = _paths.value
        if (rebuilt.isEmpty()) throw IllegalStateException("No paths after rebuild")
        return selectPath(rebuilt, exclude)
    }

    suspend fun rebuildPaths(reusablePaths: List<Path>) {
        buildMutex.withLock {
            // Double-check: Did someone populate paths while we were waiting for the lock?
            // If yes, we can skip building.
            val freshPaths = _paths.value
            if (freshPaths.size >= targetPathCount && arePathsDisjoint(freshPaths)) {
                return
            }

            _isBuilding.value = true
            Log.w("Onion Request", "Rebuilding paths...")
            try {
                val pool = directory.ensurePoolPopulated()

                val safeReusable = sanitizePaths(reusablePaths)
                val reusableGuards = safeReusable.map { it.first() }.toSet()

                val guardSnodes = directory.getGuardSnodes(
                    existingGuards = reusableGuards,
                    targetGuardCount = targetPathCount
                )

                var unused = pool
                    .minus(guardSnodes)
                    .minus(safeReusable.flatten().toSet())

                val newPaths = guardSnodes
                    .minus(reusableGuards)
                    .map { guard ->
                        val rest = (0 until pathSize - 1).map {
                            val next = unused.secureRandom()
                            unused = unused - next
                            next
                        }
                        listOf(guard) + rest
                    }

                val allPaths = (safeReusable + newPaths).take(targetPathCount)
                val sanitized = sanitizePaths(allPaths)
                _paths.value = sanitized

                // Keep strikes only for paths that still exist
                val alive = sanitized.map(::pathKey).toSet()
                pathStrikes.keys.retainAll(alive)

                Log.w("Onion Request", "Paths rebuilt successfully. Current path count: ${sanitized.size}")
            } finally {
                _isBuilding.value = false
            }
        }
    }

    /**
     * Called when we know a specific snode is bad.
     *
     * Rules:
     * - Striking a snode ALSO strikes the containing path(s).
     * - Third strike means drop snode immediately.
     * - Dropping a snode swaps it out in any path(s) that contain it (drops path only if unrepairable).
     * - Dropping a snode also removes it from pool and (if pubkey known) swarm.
     */
    suspend fun handleBadSnode(
        snode: Snode,
        publicKey: String? = null,
        forceRemove: Boolean = false
    ) {
        buildMutex.withLock {
            val paths = _paths.value.toMutableList()
            val droppedPathKeys = mutableSetOf<String>()
            val droppedSnodeKeys = mutableSetOf<String>()

            val snodeStrikes = if (forceRemove) {
                setSnodeStrikes(snode, STRIKE_THRESHOLD)
            } else {
                increaseSnodeStrike(snode)
            }

            Log.w(
                "Onion Request",
                "Bad snode reported: ${snode.address} (strikes=$snodeStrikes/$STRIKE_THRESHOLD, forceRemove=$forceRemove)"
            )

            // Striking a snode also strikes the containing path(s)
            val containing = paths.filter { it.contains(snode) }.toList()
            for (p in containing) {
                val pathStrikes = increasePathStrike(p)
                Log.w("Onion Request", "  -> Also struck containing path (strikes=$pathStrikes/$STRIKE_THRESHOLD)")

                if (pathStrikes >= STRIKE_THRESHOLD) {
                    Log.w("Onion Request", "  -> Path hit threshold due to snode strike, dropping path (cascade)")
                    performPathDrop(
                        path = p,
                        paths = paths,
                        publicKey = publicKey,
                        droppedPathKeys = droppedPathKeys,
                        droppedSnodeKeys = droppedSnodeKeys,
                    )
                }
            }

            // If snode reached strike threshold => drop snode
            if (snodeStrikes >= STRIKE_THRESHOLD) {
                Log.w("Onion Request", "Strike threshold reached for snode ${snode.address}, initiating drop cascade")
                performSnodeDrop(
                    snode = snode,
                    working = paths,
                    publicKey = publicKey,
                    droppedPathKeys = droppedPathKeys,
                    droppedSnodeKeys = droppedSnodeKeys,
                )
            }

            _paths.value = sanitizePaths(paths)
        }
    }

    /**
     * Called when an entire path is considered unreliable.
     *
     * Rules:
     * - Third strike means drop path immediately.
     * - Dropping a path strikes each node in the path (which can cascade into node drops).
     */
    suspend fun handleBadPath(path: Path) {
        buildMutex.withLock {
            val paths = _paths.value.toMutableList()
            val target = paths.firstOrNull { it == path } ?: run {
                Log.w("Onion Request", "Attempted to strike path not in current list, ignoring")
                return
            }

            val pathStrikes = increasePathStrike(target)
            Log.w("Onion Request", "Bad path reported (strikes=$pathStrikes/$STRIKE_THRESHOLD)")

            if (pathStrikes < STRIKE_THRESHOLD) return

            Log.w("Onion Request", "Strike threshold reached for path, initiating drop cascade")

            val droppedPathKeys = mutableSetOf<String>()
            val droppedSnodeKeys = mutableSetOf<String>()

            performPathDrop(
                path = target,
                paths = paths,
                publicKey = null, // handleBadPath has no swarm context
                droppedPathKeys = droppedPathKeys,
                droppedSnodeKeys = droppedSnodeKeys,
            )

            _paths.value = sanitizePaths(paths)
        }
    }

    /**
     * Drops a path immediately and strikes all nodes within it.
     * If any node reaches threshold, drops that node (which swaps it out in any remaining paths).
     */
    private suspend fun performPathDrop(
        path: Path,
        paths: MutableList<Path>,
        publicKey: String?,
        droppedPathKeys: MutableSet<String>,
        droppedSnodeKeys: MutableSet<String>,
    ) {
        if (!paths.contains(path)) return

        val pk = pathKey(path)
        if (!droppedPathKeys.add(pk)) return // already dropped in this cascade

        Log.w("Onion Request", "Dropping path: ${path.joinToString(" -> ") { it.address }}")
        paths.remove(path)
        clearPathStrike(path)

        // Dropping a path strikes each node in that path (may cascade)
        for (node in path) {
            val sStrikes = increaseSnodeStrike(node)
            Log.w("Onion Request", "  Struck node ${node.address} from dropped path (strikes=$sStrikes/$STRIKE_THRESHOLD)")

            if (sStrikes >= STRIKE_THRESHOLD) {
                Log.w("Onion Request", "  Node ${node.address} hit threshold from path drop, dropping snode (cascade)")
                performSnodeDrop(
                    snode = node,
                    working = paths,
                    publicKey = publicKey,
                    droppedPathKeys = droppedPathKeys,
                    droppedSnodeKeys = droppedSnodeKeys,
                )
            }
        }
    }

    /**
     * Drops a snode from external systems and swaps it out of any paths that contain it.
     * Only drops a path if it cannot be repaired.
     *
     * This does NOT rebuild paths; it swaps only the bad node.
     */
    private suspend fun performSnodeDrop(
        snode: Snode,
        working: MutableList<Path>,
        publicKey: String?,
        droppedPathKeys: MutableSet<String>,
        droppedSnodeKeys: MutableSet<String>,
    ) {
        val sk = snodeKey(snode)
        if (!droppedSnodeKeys.add(sk)) return // already dropped in this cascade

        Log.w("Onion Request", "Dropping snode ${snode.address} from systems + swapping out of paths")

        // External cleanup (pool + swarm)
        snode.publicKeySet?.ed25519Key?.let { ed25519 ->
            directory.dropSnodeFromPool(ed25519)
            Log.d("Onion Request", "  Removed snode from pool: ${snode.address}")
        }
        if (publicKey != null) {
            swarmDirectory.dropSnodeFromSwarmIfNeeded(snode, publicKey)
            Log.d("Onion Request", "  Removed snode from swarm for pubkey=$publicKey: ${snode.address}")
        }

        // Swap out in any path(s) that still contain it
        val pathsToCheck = working.filter { it.contains(snode) }.toList()
        for (path in pathsToCheck) {
            val result = trySwapOutSnodeInPath(path, snode, working)

            if (result != null) {
                val (repaired, replacement) = result
                val idx = working.indexOf(path)
                if (idx != -1) {
                    // Path identity changes; clear strikes for the old path identity
                    clearPathStrike(path)
                    working[idx] = repaired
                    Log.w("Onion Request", "  Repaired path by swapping ${snode.address} -> ${replacement.address}")
                }
            } else {
                Log.w("Onion Request", "  Could not repair path after snode drop; dropping path (cascade)")
                performPathDrop(
                    path = path,
                    paths = working,
                    publicKey = publicKey,
                    droppedPathKeys = droppedPathKeys,
                    droppedSnodeKeys = droppedSnodeKeys,
                )
            }
        }

        // Clear strike once it's dropped and we've finished processing
        clearSnodeStrike(snode)
    }

    /**
     * Swap a single bad snode out of a path, respecting disjointness:
     * - cannot use nodes already in other paths
     * - cannot use nodes already in this path (except the bad one)
     * - cannot use the bad node itself
     *
     * @return Pair(repairedPath, replacementSnode) or null if no replacement available.
     */
    private fun trySwapOutSnodeInPath(
        path: Path,
        badSnode: Snode,
        currentPaths: List<Path>,
    ): Pair<Path, Snode>? {
        val index = path.indexOfFirst { it == badSnode }
        if (index == -1) return null

        val pool = directory.getSnodePool()

        val usedByOtherPaths = currentPaths
            .filter { it != path }
            .flatMap { it }
            .toSet()

        val usedInThisPath = path.toSet() - badSnode
        val forbidden = usedByOtherPaths + usedInThisPath + badSnode

        val candidates = pool - forbidden
        if (candidates.isEmpty()) {
            Log.w("Onion Request", "  No available snodes for path repair")
            return null
        }

        val replacement = candidates.secureRandom()
        val repaired = path.toMutableList()
        repaired[index] = replacement

        Log.d("Onion Request", "  Path repair: ${badSnode.address} -> ${replacement.address}")
        return repaired to replacement
    }

    private fun selectPath(paths: List<Path>, exclude: Snode?): Path {
        val candidates = if (exclude != null) {
            paths.filter { !it.contains(exclude) }
        } else paths

        if (candidates.isEmpty()) {
            Log.w("Onion Request", "No valid paths excluding requested snode, using any available path")
            return paths.secureRandom()
        }
        return candidates.secureRandom()
    }

    private fun sanitizePaths(paths: List<Path>): List<Path> {
        if (paths.isEmpty()) return emptyList()
        if (arePathsDisjoint(paths)) return paths
        Log.w("Onion Request", "Paths contained overlapping snodes. Dropping backups.")
        return paths.take(1)
    }

    private fun arePathsDisjoint(paths: List<Path>): Boolean {
        val all = paths.flatten()
        return all.size == all.toSet().size
    }
}
