package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.*
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.MockLoggingRule

class PathManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    private fun snode(id: String): Snode =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet(ed25519Key = "ed_$id", x25519Key = "x_$id"),
            version = Snode.Version.ZERO
        )

    private class FakePathStorage(initial: List<Path>) : SnodePathStorage {
        private var value: List<Path> = initial
        var lastSet: List<Path>? = null
        var cleared = false

        override fun getOnionRequestPaths(): List<Path> = value

        override fun setOnionRequestPaths(paths: List<Path>) {
            value = paths
            lastSet = paths
        }

        override fun clearOnionRequestPaths() {
            value = emptyList()
            cleared = true
        }
    }

    @Test
    fun `init sanitize drops backup when overlapping`() = runTest {
        val a = snode("a"); val b = snode("b"); val c = snode("c"); val d = snode("d")

        val p1: Path = listOf(a, b, c)
        val p2: Path = listOf(a, d, c) // overlaps

        val storage = FakePathStorage(listOf(p1, p2))
        val directory = mock<SnodeDirectory>()
        val swarmDirectory = mock<SwarmDirectory>()

        val pm = PathManager(
            scope = backgroundScope,
            directory = directory,
            storage = storage,
            swarmDirectory = swarmDirectory
        )

        assertThat(pm.paths.value).hasSize(1)
        assertThat(pm.paths.value.first()).isEqualTo(p1)
    }

    @Test
    fun `getPath excludes node when possible`() = runTest {
        val a = snode("a"); val b = snode("b"); val c = snode("c")
        val d = snode("d"); val e = snode("e"); val f = snode("f")

        val p1: Path = listOf(a, b, c)
        val p2: Path = listOf(d, e, f)

        val storage = FakePathStorage(listOf(p1, p2))
        val directory = mock<SnodeDirectory>()
        val swarmDirectory = mock<SwarmDirectory>()

        val pm = PathManager(backgroundScope, directory, storage, swarmDirectory)

        val chosen = pm.getPath(exclude = b)
        assertThat(chosen).isEqualTo(p2)
    }

    @Test
    fun `forceRemove drops snode from pool and swarm and repairs path when possible`() = runTest {
        val a = snode("a"); val b = snode("b"); val c = snode("c")
        val d = snode("d"); val e = snode("e"); val f = snode("f")
        val x = snode("x") // replacement candidate

        val p1: Path = listOf(a, b, c)
        val p2: Path = listOf(d, e, f)

        val storage = FakePathStorage(listOf(p1, p2))

        val directory = mock<SnodeDirectory> {
            // repair uses getSnodePool()
            on { getSnodePool() } doReturn setOf(a,b,c,d,e,f,x)
        }
        val swarmDirectory = mock<SwarmDirectory>()

        val pm = PathManager(backgroundScope, directory, storage, swarmDirectory)

        pm.handleBadSnode(snode = b, swarmPublicKey = "pubkey123", forceRemove = true)
        advanceUntilIdle()

        val newPaths = pm.paths.value
        assertThat(newPaths).hasSize(2)
        assertThat(newPaths.flatten()).doesNotContain(b)

        // verify external cleanup
        verify(directory).dropSnodeFromPool("ed_b") // called when ed25519 present :contentReference[oaicite:9]{index=9}
        verify(swarmDirectory).dropSnodeFromSwarmIfNeeded(b, "pubkey123") // pubKey context :contentReference[oaicite:10]{index=10}

        // disjoint invariant
        val flat = newPaths.flatten()
        assertThat(flat.toSet().size).isEqualTo(flat.size)
    }

    @Test
    fun `forceRemove drops path when no replacement candidate exists`() = runTest {
        val a = snode("a"); val b = snode("b"); val c = snode("c")
        val d = snode("d"); val e = snode("e"); val f = snode("f")

        val p1: Path = listOf(a, b, c)
        val p2: Path = listOf(d, e, f)

        val storage = FakePathStorage(listOf(p1, p2))

        val directory = mock<SnodeDirectory> {
            // pool has only used nodes, so no candidates remain after forbidding
            on { getSnodePool() } doReturn setOf(a,b,c,d,e,f)
        }
        val swarmDirectory = mock<SwarmDirectory>()

        val pm = PathManager(backgroundScope, directory, storage, swarmDirectory)

        pm.handleBadSnode(snode = b, swarmPublicKey = "pubkey123", forceRemove = true)
        advanceUntilIdle()

        val newPaths = pm.paths.value
        assertThat(newPaths.flatten()).doesNotContain(b)
        assertThat(newPaths.size).isLessThan(2) // irreparable path dropped :contentReference[oaicite:11]{index=11}
    }
}