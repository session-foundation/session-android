package org.thoughtcrime.securesms.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import org.session.libsession.network.snode.SnodePoolStorage
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.snode.GetInfoApi
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiRequest
import org.thoughtcrime.securesms.database.SnodeDatabase
import org.thoughtcrime.securesms.database.SnodeDatabaseTest
import org.thoughtcrime.securesms.util.MockLoggingRule
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 36) // Setting min sdk 36 to use recent sqlite version as we use some modern features in the app code
class PathManagerTest {

    @get:Rule
    val logRule = MockLoggingRule()

    lateinit var snodeDb: SnodeDatabase

    private lateinit var networkConnectivity: NetworkConnectivity

    @Before
    fun setUp() {
        snodeDb = SnodeDatabaseTest.createInMemorySnodeDatabase()

        networkConnectivity = mock {
            on { networkAvailable } doReturn MutableStateFlow(true)
        }
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
            getInfoApi = { mock() },
            networkConnectivity = networkConnectivity,
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
            getInfoApi = { mock() },
            networkConnectivity = networkConnectivity,
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
            getInfoApi = { mock() },
            networkConnectivity = networkConnectivity,
        )

        pm.handleBadSnode(snode = b, forceRemove = true)

        val newPaths = pm.paths.value
        assertThat(newPaths.flatten()).doesNotContain(b)
        assertThat(newPaths.size).isLessThan(2) // irreparable path dropped :contentReference[oaicite:11]{index=11}
    }

    /**
     * Rotation is what these two tests actually drive, because it is the only caller of the path
     * test: it builds candidate paths, tests each one, and commits only if every candidate passed.
     *
     * [rejecting] is the set of destinations whose path test fails; every other destination
     * succeeds. Returns the manager plus the list of destinations it has been asked to talk to.
     */
    private fun TestScope.rotatingPathManager(
        pool: List<Snode>,
        persistedPaths: List<Path>,
        rejecting: Set<Snode>,
    ): Triple<PathManager, List<Snode>, CoroutineScope> {
        val targeted = mutableListOf<Snode>()

        val executor: SnodeApiExecutor = mock {
            onBlocking { send(any(), any()) } doAnswer { invocation ->
                val request = invocation.getArgument<SnodeApiRequest<*>>(1)
                targeted += request.snode
                if (request.snode in rejecting) throw IOException("synthetic rejection")
                GetInfoApi.InfoResponse(timestamp = Instant.EPOCH)
            }
        }

        val poolStorage: SnodePoolStorage = mock {
            on { getSnodePool() } doReturn pool
        }

        val directory: SnodeDirectory = mock {
            onBlocking { ensurePoolPopulated(any()) } doReturn pool
        }

        // Anything other than 0 counts as "rotated once, long ago": 0 means never rotated, which
        // only records a start time. Nothing writes this back, so every getPath() rotates again.
        val prefs: TextSecurePreferences = mock {
            on { getLastPathRotation() } doReturn 1L
        }

        snodeDb.setSnodePool(pool.toSet())
        snodeDb.setOnionRequestPaths(persistedPaths)

        // Not backgroundScope: advanceUntilIdle() stops once no foreground task is left, so
        // rotation launched into a background scope would never run. Cancelled by the caller.
        val pmScope = CoroutineScope(StandardTestDispatcher(testScheduler))

        val pm = PathManager(
            scope = pmScope,
            directory = directory,
            storage = snodeDb,
            snodePoolStorage = poolStorage,
            prefs = prefs,
            snodeApiExecutor = { executor },
            getInfoApi = { mock() },
            networkConnectivity = networkConnectivity,
        )

        return Triple(pm, targeted, pmScope)
    }

    @Test
    fun `path tests do not all target the same snode`() = runTest {
        val p1: Path = listOf(snode("a"), snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val spares = (1..12).map { snode("spare$it") }

        // b heads the pool and is eligible for every path test: rotation keeps the guards and draws
        // replacements from nodes no current path uses, so a current non-guard node can never land
        // in a candidate. Picking the first eligible member therefore lands on b every time.
        val pool = listOf(snode("b")) + spares + listOf(snode("a"), snode("c")) + p2

        // Every destination rejects, so no rotation commits and all eight sample the same position.
        val (pm, targeted, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejecting = pool.toSet(),
        )

        repeat(8) {
            pm.getPath()
            advanceUntilIdle()
        }
        pmScope.cancel()

        assertThat(targeted).hasSize(16) // two candidate paths tested per rotation
        assertThat(targeted.toSet().size).isGreaterThan(1)
    }

    @Test
    fun `rotation commits even though the first pool member rejects every request`() = runTest {
        val p1: Path = listOf(snode("a"), snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val broken = snode("b") // in a current path and not a guard: see the test above
        val spares = (1..12).map { snode("spare$it") }

        val pool = listOf(broken) + spares + listOf(snode("a"), snode("c")) + p2

        val (pm, targeted, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejecting = setOf(broken),
        )

        // A rotation that happens to pick the broken node for either candidate fails, so this
        // retries rather than asserting on one attempt. Ten attempts make a false failure
        // vanishingly unlikely, while a fixed destination cannot pass any of them.
        // paths is empty until the first getPath() warms it up from storage, so an unrotated state
        // is either of those two values.
        var attempts = 0
        while (attempts < 10 && pm.paths.value.let { it.isEmpty() || it == listOf(p1, p2) }) {
            pm.getPath()
            advanceUntilIdle()
            attempts++
        }
        pmScope.cancel()

        assertThat(targeted).isNotEmpty()
        assertThat(pm.paths.value).isNotEqualTo(listOf(p1, p2))
        assertThat(pm.paths.value).hasSize(2)
        assertThat(pm.paths.value.map { it.first() }).containsExactly(p1.first(), p2.first())
    }
}
