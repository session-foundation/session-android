package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.session.libsession.network.model.Path
import org.session.libsession.network.onion.PathManager
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.SnodeDatabase
import org.thoughtcrime.securesms.database.SnodeDatabaseTest
import org.thoughtcrime.securesms.util.MockLoggingRule

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 36) // Setting min sdk 36 to use recent sqlite version as we use some modern features in the app code
class PathManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    lateinit var snodeDb: SnodeDatabase

    @Before
    fun setUp() {
        snodeDb = SnodeDatabaseTest.createInMemorySnodeDatabase()
    }

    private fun snode(id: String): Snode =
        Snode(
            address = "https://$id.example",
            port = 443,
            publicKeySet = Snode.KeySet(ed25519Key = "ed_$id", x25519Key = "x_$id"),
        )


    @Test
    fun `getPath excludes node when possible`() = runTest {
        val a = snode("a"); val b = snode("b"); val c = snode("c")
        val d = snode("d"); val e = snode("e"); val f = snode("f")

        val p1: Path = listOf(a, b, c)
        val p2: Path = listOf(d, e, f)

        snodeDb.setSnodePool(setOf(a,b,c,d,e,f))
        snodeDb.setOnionRequestPaths(listOf(p1, p2))

        val pm = PathManager(
            scope = backgroundScope,
            directory = mock(),
            storage = snodeDb,
            snodePoolStorage = snodeDb,
            prefs = mock(),
            snodeApiExecutor = { mock() },
            getNetworkTimeApi = { mock() },
        )

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

        snodeDb.setSnodePool(setOf(a,b,c,d,e,f,x))
        snodeDb.setOnionRequestPaths(listOf(p1, p2))

        val pm = PathManager(
            scope = backgroundScope,
            directory = mock(),
            storage = snodeDb,
            snodePoolStorage = snodeDb,
            prefs = mock(),
            snodeApiExecutor = { mock() },
            getNetworkTimeApi = { mock() },
        )

        pm.handleBadSnode(snode = b, forceRemove = true)

        val newPaths = pm.paths.value
        assertThat(newPaths).hasSize(2)
        assertThat(newPaths.flatten()).doesNotContain(b)

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

        snodeDb.setSnodePool(setOf(a,b,c,d,e,f))
        snodeDb.setOnionRequestPaths(listOf(p1, p2))

        val pm = PathManager(
            scope = backgroundScope,
            directory = mock(),
            storage = snodeDb,
            snodePoolStorage = snodeDb,
            prefs = mock(),
            snodeApiExecutor = { mock() },
            getNetworkTimeApi = { mock() },
        )

        pm.handleBadSnode(snode = b, forceRemove = true)

        val newPaths = pm.paths.value
        assertThat(newPaths.flatten()).doesNotContain(b)
        assertThat(newPaths.size).isLessThan(2) // irreparable path dropped :contentReference[oaicite:11]{index=11}
    }
}