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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.session.libsession.network.model.Path
import org.session.libsession.network.model.PathStatus
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SnodePathStorage
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
) {
    private val pathSize: Int = 3
    private val targetPathCount: Int = 2

    private val _paths = MutableStateFlow(
        sanitizePaths(storage.getOnionRequestPaths())
    )
    val paths: StateFlow<List<Path>> = _paths.asStateFlow()

    // Used for synchronization
    private val buildMutex = Mutex()

    private val _isBuilding = MutableStateFlow(false)

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

    suspend fun getPath(exclude: Snode? = null): Path {
        val current = _paths.value
        if (current.size >= targetPathCount && current.any { exclude == null || !it.contains(exclude) }) {
            return selectPath(current, exclude)
        }

        // Wait for rebuild to finish if one is happening, or start one
        rebuildPaths(reusablePaths = current)

        val rebuilt = _paths.value
        if (rebuilt.isEmpty()) throw IllegalStateException("No paths after rebuild")
        return selectPath(rebuilt, exclude)
    }

    suspend fun rebuildPaths(reusablePaths: List<Path>) {
        // This ensures callers wait their turn rather than skipping immediately
        buildMutex.withLock {
            // Double-check: Did someone populate paths while we were waiting for the lock?
            // If yes, we can skip building.
            val freshPaths = _paths.value
            if (freshPaths.size >= targetPathCount && arePathsDisjoint(freshPaths)) {
                return
            }

            _isBuilding.value = true
            try {
                // Ensure we actually have a usable pool before doing anything
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
            } finally {
                _isBuilding.value = false
            }
        }
    }

    /** Called when we know a specific snode is bad. */
    fun handleBadSnode(snode: Snode) {
        _paths.update { currentList ->
            // Locate the bad path in the *current* snapshot
            val pathIndex = currentList.indexOfFirst { it.contains(snode) }

            // If the node isn't found (e.g., paths were just rebuilt), do nothing
            if (pathIndex == -1) return@update currentList

            // Prepare mutable copies for modification
            // We copy the outer list so we don't mutate the 'currentList' which might be needed for a CAS retry
            val newPathsList = currentList.toMutableList()
            val pathParams = newPathsList[pathIndex].toMutableList()

            // Remove the bad node
            pathParams.remove(snode)

            // Find a replacement
            val usedSnodes = newPathsList.flatten().toSet()
            val pool = directory.getSnodePool()
            val unused = pool.minus(usedSnodes)

            if (unused.isEmpty()) {
                Log.w("Onion", "No unused snodes to repair path, dropping path entirely")
                newPathsList.removeAt(pathIndex)
            } else {
                val replacement = unused.secureRandom()
                pathParams.add(replacement)
                newPathsList[pathIndex] = pathParams
            }

            // Return the new clean list
            sanitizePaths(newPathsList)
        }
    }

    /** Called when an entire path is considered unreliable. */
    fun handleBadPath(path: Path) {
        _paths.update { currentList ->
            // Filter returns a new list, so this is safe and atomic
            currentList.filter { it != path }
        }
    }

    private fun selectPath(paths: List<Path>, exclude: Snode?): Path {
        val candidates = if (exclude != null) {
            paths.filter { !it.contains(exclude) }
        } else paths

        if (candidates.isEmpty()) {
            // fallback: ignore exclude and just pick something
            return paths.secureRandom()
        }

        return candidates.secureRandom()
    }

    private fun sanitizePaths(paths: List<Path>): List<Path> {
        if (paths.isEmpty()) return emptyList()
        if (arePathsDisjoint(paths)) return paths
        Log.w("Onion", "Paths contained overlapping snodes. Dropping backups.")
        return paths.take(1)
    }

    private fun arePathsDisjoint(paths: List<Path>): Boolean {
        val all = paths.flatten()
        return all.size == all.toSet().size
    }
}