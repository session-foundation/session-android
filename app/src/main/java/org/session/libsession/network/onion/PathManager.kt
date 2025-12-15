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
import org.session.libsession.network.model.Path
import org.session.libsession.network.model.PathStatus
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode

class PathManager(
    private val scope: CoroutineScope,
    private val directory: SnodeDirectory,
    private val storage: SnodePathStorage,
    private val pathSize: Int = 3,
    private val targetPathCount: Int = 2,
) {

    private val _paths = MutableStateFlow(
        sanitizePaths(storage.getOnionRequestPaths())
    )
    val paths: StateFlow<List<Path>> = _paths.asStateFlow()

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

        // Need to (re)build paths
        rebuildPaths(reusablePaths = current)
        val rebuilt = _paths.value
        if (rebuilt.isEmpty()) throw IllegalStateException("No paths after rebuild")
        return selectPath(rebuilt, exclude)
    }

    suspend fun rebuildPaths(reusablePaths: List<Path>) {
        if (_isBuilding.value) return

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

    /** Called when we know a specific snode is bad. */
    fun handleBadSnode(snode: Snode) {
        val current = _paths.value.toMutableList()
        val pathIndex = current.indexOfFirst { it.contains(snode) }
        if (pathIndex == -1) return

        val path = current[pathIndex].toMutableList()
        path.remove(snode)

        val unused = directory.getSnodePool().minus(current.flatten().toSet())
        if (unused.isEmpty()) {
            Log.w("Onion", "No unused snodes to repair path, dropping path entirely")
            current.removeAt(pathIndex)
            _paths.value = current
            return
        }

        val replacement = unused.secureRandom()
        path.add(replacement)
        current[pathIndex] = path
        _paths.value = sanitizePaths(current)
    }

    /** Called when an entire path is considered unreliable. */
    fun handleBadPath(path: Path) {
        val current = _paths.value.toMutableList()
        current.remove(path)
        _paths.value = current
        // Next call to getPath() will trigger rebuild if needed
    }

    // --- helpers ---

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
