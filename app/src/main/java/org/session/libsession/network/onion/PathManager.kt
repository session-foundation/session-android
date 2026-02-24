package org.session.libsession.network.onion

import kotlinx.coroutines.CancellationException
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

import kotlinx.coroutines.withTimeoutOrNull
import org.session.libsession.network.model.Path
import org.session.libsession.network.model.PathStatus
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SnodePoolStorage
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.SystemPreferences
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.AutoRetryApiExecutor
import org.thoughtcrime.securesms.api.onion.OnionSessionApiExecutor
import org.thoughtcrime.securesms.api.snode.GetInfoApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.api.snode.execute
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
open class PathManager @Inject constructor(
    private val scope: CoroutineScope,
    private val directory: SnodeDirectory,
    private val storage: SnodePathStorage,
    private val snodePoolStorage: SnodePoolStorage,
    private val preferenceStorage: PreferenceStorage,
    private val snodeApiExecutor: Provider<SnodeApiExecutor>,
    private val getInfoApi: Provider<GetInfoApi>,
) {
    companion object {
        private const val STRIKE_THRESHOLD = 3
        private const val PATH_ROTATE_INTERVAL_MS = 10 * 60 * 1000L // 10min
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

    // path rotation
    private val isRotating = AtomicBoolean(false)

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
                else {
                    try {
                        storage.setOnionRequestPaths(paths)
                    } catch (e: Exception) {
                        Log.e("Onion Request", "Failed to persist paths to storage, keeping in-memory only", e)
                    }
                }
            }
        }
    }

    // -----------------------------
    // Public API
    // -----------------------------

    suspend fun getPath(exclude: Snode? = null): Path {
        directory.refreshPoolIfStaleAsync()
        rotatePathsIfStale()

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

    private fun rotatePathsIfStale() {
        val now = System.currentTimeMillis()
        val last = preferenceStorage[SystemPreferences.LAST_PATH_ROTATION]

        // if we have never done a path rotation, mark now as the starting time
        // so we can rotate on the next tick
        if (last == 0L){
            preferenceStorage[SystemPreferences.LAST_PATH_ROTATION] = now
            return
        }

        // no rotation needed if it's been less than our interval time
        if (now - last < PATH_ROTATE_INTERVAL_MS) return

        if (!isRotating.compareAndSet(false, true)) return
        scope.launch {
            try {
                rotatePaths()
            } finally {
                isRotating.set(false)
            }
        }
    }

    private suspend fun testPath(pathCandidate: Path): Boolean {
        try {
            return withTimeoutOrNull(5.seconds) {
                // Run an snode API to test the path but disable path manipulation, retries, etc.
                snodeApiExecutor.get()
                    .execute(
                        req = SnodeApiRequest(
                            snode = snodePoolStorage.getSnodePool().first { it !in pathCandidate },
                            api = getInfoApi.get()
                        ),
                        ctx = ApiExecutorContext()
                            .set(OnionSessionApiExecutor.OnionPathOverridesKey, pathCandidate)
                            .set(AutoRetryApiExecutor.DisableRetryKey, Unit)
                    )

                Log.d("Onion Request", "Path test succeeded for candidate path $pathCandidate")
            } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("Onion Request", "Path test failed for $pathCandidate", e)
            return false
        }
    }

    /**
     * Rotates existing paths by keeping the guard node but replacing the rest.
     * Validates connectivity via /info before committing.
     */
    private suspend fun rotatePaths() {
        Log.d("Onion Request", "Start rotating paths...")

        // Phase 1: decide + build candidates under lock
        val candidates: List<Path> = buildMutex.withLock {
            val now = System.currentTimeMillis()
            val last = preferenceStorage[SystemPreferences.LAST_PATH_ROTATION]
            if (now - last < PATH_ROTATE_INTERVAL_MS) return@withLock emptyList()

            val current = _paths.value
            if (current.isEmpty()) return@withLock emptyList()

            if (current.size < targetPathCount) {
                // Don't rotate if we're already degraded - rebuildPaths will handle this.
                return@withLock emptyList()
            }

            // Keep the same guards
            val guards = current.take(targetPathCount).map { it.first() }

            val pool = directory.ensurePoolPopulated()

            // do not reuse path snodes
            val avoid = current.flatten().toSet()

            var unused = pool.minus(avoid)

            val neededPerPath = pathSize - 1
            val totalNeeded = targetPathCount * neededPerPath
            if (unused.size < totalNeeded) {
                // Not enough to rotate cleanly, skip silently.
                return@withLock emptyList()
            }

            val rotated = guards.map { guard ->
                val rest = (0 until neededPerPath).map {
                    val next = unused.secureRandom()
                    unused = unused - next
                    next
                }
                listOf(guard) + rest
            }

            sanitizePaths(rotated)
        }

        if (candidates.isEmpty()) return

        // Phase 2: test out our paths
        val working = ArrayList<Path>(targetPathCount)
        for (p in candidates) {
            if (testPath(p)) {
                working += p
            }
            if (working.size >= targetPathCount) break
        }

        if (working.isEmpty()) return

        // Phase 3: commit under lock (guards must match current guards)
        buildMutex.withLock {
            val current = sanitizePaths(_paths.value)
            if (current.isEmpty()) return@withLock

            val currentGuards = current.take(targetPathCount).map { it.first() }.toSet()
            val newGuards = working.map { it.first() }.toSet()

            if (currentGuards != newGuards) {
                // Guards changed (likely rebuild happened)
                Log.i("Onion Request", "Rotation aborted: Guards changed during validation (likely due to concurrent repair).")
                return@withLock
            }

            Log.d("Onion Request", "New rotated paths validated, committing as: $working")

            val committed = sanitizePaths(working.take(targetPathCount))
            _paths.value = committed
            preferenceStorage[SystemPreferences.LAST_PATH_ROTATION] = System.currentTimeMillis()
        }
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
     *
     * @return true if the snode was punished/removed, false if it was not found in pool.
     */
    suspend fun handleBadSnode(
        snode: Snode,
        forceRemove: Boolean = false
    ) {
        buildMutex.withLock {
            val shouldRemoveSnode = forceRemove ||
                    snodePoolStorage.increaseSnodeStrike(snode, 1) == STRIKE_THRESHOLD

            // First we need to punish the path that contains this snode
            val pathWithStrikes = storage.increaseOnionRequestPathStrikeContainingSnode(
                snodeEd25519PubKey = snode.ed25519Key,
                increment = 1
            )

            when {
                // If containing path hit remove threshold, remove the path without trying to repair
                pathWithStrikes != null && pathWithStrikes.second >= STRIKE_THRESHOLD -> {
                    storage.removePath(pathWithStrikes.first)
                }

                // If containing path did not hit remove threshold, and we need to remove the snode,
                // we try to repair the path by swapping out the bad snode
                pathWithStrikes != null && shouldRemoveSnode -> {
                    val replacementSnode = storage.findRandomUnusedSnodesForNewPath(1)
                        .firstOrNull()

                    val containingPath = pathWithStrikes.first

                    if (replacementSnode == null) {
                        // No replacement available, remove the path
                        storage.removePath(containingPath)
                    } else {
                        val repairedPath = pathWithStrikes.first.map { node ->
                            if (node == snode) replacementSnode else node
                        }

                        // Swap in the repaired path
                        storage.replaceOnionRequestPath(
                            oldPath =  containingPath,
                            newPath = repairedPath
                        )
                    }
                }
            }

            if (shouldRemoveSnode) {
                // Now we should be able to drop the snode from pool and swarm
                snodePoolStorage.removeSnode(snode.ed25519Key)
            }

            _paths.value = storage.getOnionRequestPaths()

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
            val newPathStrike = storage.increaseOnionRequestPathStrike(path, 1)

            if (newPathStrike == null) {
                Log.w("Onion Request", "Attempted to strike path not in storage, ignoring")
                return
            }

            // First, strike each node in the path and find out what nodes need to be removed
            val nodesToRemove = path.filter { node ->
                val nodeStrike = snodePoolStorage.increaseSnodeStrike(node, 1)
                nodeStrike != null && nodeStrike >= STRIKE_THRESHOLD
            }

            // Find out if we need to remove the path
            if (newPathStrike >= STRIKE_THRESHOLD || nodesToRemove.size == path.size) {
                Log.w("Onion Request", "Path hit strike threshold or all snodes need removing, dropping path")
                storage.removePath(path)
            } else if (nodesToRemove.isNotEmpty()) {
                // We have partial nodes to remove, so we will look at their replacements in paths
                val newNodes = storage.findRandomUnusedSnodesForNewPath(nodesToRemove.size)
                if (newNodes.size < nodesToRemove.size) {
                    Log.w("Onion Request", "Not enough available snodes to replace bad nodes in path, dropping path")
                    storage.removePath(path)
                } else {
                    // Swap out the bad nodes in the path
                    val repairedPath = path.map { node ->
                        val idx = nodesToRemove.indexOfFirst { it == node }
                        if (idx != -1) {
                            newNodes[idx]
                        } else {
                            node
                        }
                    }

                    Log.w("Onion Request", "Repaired path by swapping out bad nodes: ${nodesToRemove.map { it.address }}")
                    // Update storage with repaired path
                    storage.replaceOnionRequestPath(oldPath = path, newPath = repairedPath)
                }
            }

            // It's now safe to remove the snodes from pool
            nodesToRemove.forEach { snode ->
                snodePoolStorage.removeSnode(snode.ed25519Key)
            }

            _paths.value = sanitizePaths(storage.getOnionRequestPaths())
        }
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
