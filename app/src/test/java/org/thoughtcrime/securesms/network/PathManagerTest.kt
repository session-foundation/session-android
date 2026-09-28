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
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.onion.OnionSessionApiExecutor
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

    private data class RotationHarness(
        val pathManager: PathManager,
        val targeted: List<Snode>,
        val directory: SnodeDirectory,
        val scope: CoroutineScope,
    )

    /**
     * Rotation is what these tests actually drive, because it is the only caller of the path
     * test: it builds candidate paths, tests each one, and commits only if every candidate passed.
     *
     * [rejecting] is the set of destinations whose path test fails; every other destination
     * succeeds. Returns the manager plus the list of destinations it has been asked to talk to.
     */
    private fun TestScope.rotatingPathManager(
        pool: List<Snode>,
        persistedPaths: List<Path>,
        rejecting: Set<Snode> = emptySet(),
        rejectingGuards: Set<Snode> = emptySet(),
        rebuildGuards: Set<Snode> = emptySet(),
        networkAvailable: Boolean = true,
    ): RotationHarness {
        val targeted = mutableListOf<Snode>()

        // A path test carries the candidate it is testing in the request context, so a failure can be
        // attributed to that candidate's guard - which is what a bad first hop looks like from here.
        val executor: SnodeApiExecutor = mock {
            onBlocking { send(any(), any()) } doAnswer { invocation ->
                val ctx = invocation.getArgument<ApiExecutorContext>(0)
                val request = invocation.getArgument<SnodeApiRequest<*>>(1)
                val candidate = ctx.get(OnionSessionApiExecutor.OnionPathOverridesKey)
                targeted += request.snode
                when {
                    candidate?.first() in rejectingGuards -> throw IOException("synthetic guard rejection")
                    request.snode in rejecting -> throw IOException("synthetic rejection")
                    else -> GetInfoApi.InfoResponse(timestamp = Instant.EPOCH)
                }
            }
        }

        val poolStorage: SnodePoolStorage = mock {
            on { getSnodePool() } doReturn pool
        }

        val directory: SnodeDirectory = mock {
            onBlocking { ensurePoolPopulated(any()) } doReturn pool
            onBlocking { getGuardSnodes(any(), any()) } doReturn rebuildGuards
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
            networkConnectivity = mock {
                on { this.networkAvailable } doReturn MutableStateFlow(networkAvailable)
            },
        )

        return RotationHarness(pm, targeted, directory, pmScope)
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

        // Every destination rejects, so no rotation commits and both sample the same position.
        // Two rotations, not more: a third would trip the escalation below and drop these paths.
        val (pm, targeted, _, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejecting = pool.toSet(),
        )

        repeat(2) {
            pm.getPath()
            advanceUntilIdle()
        }
        pmScope.cancel()

        assertThat(targeted).hasSize(4) // two candidate paths tested per rotation
        assertThat(targeted.toSet().size).isGreaterThan(1)
    }

    @Test
    fun `rotation commits even though the first pool member rejects every request`() = runTest {
        val p1: Path = listOf(snode("a"), snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val broken = snode("b") // in a current path and not a guard: see the test above
        val spares = (1..12).map { snode("spare$it") }

        val pool = listOf(broken) + spares + listOf(snode("a"), snode("c")) + p2

        val (pm, targeted, _, pmScope) = rotatingPathManager(
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

    @Test
    fun `rotation that never verifies drops the paths and rebuilds onto fresh guards`() = runTest {
        val p1: Path = listOf(snode("a"), snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val spares = (1..12).map { snode("spare$it") }
        val pool = spares + p1 + p2
        val freshGuards = setOf(snode("fresh1"), snode("fresh2"))

        val (pm, _, directory, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejecting = pool.toSet(),
            rebuildGuards = freshGuards,
        )

        repeat(3) {
            pm.getPath()
            advanceUntilIdle()
        }

        assertThat(pm.paths.value).isEmpty()
        assertThat(snodeDb.getOnionRequestPaths()).isEmpty()

        pm.getPath()
        advanceUntilIdle()
        pmScope.cancel()

        // The guards are new because the rebuild was given none to reuse - that is the step that
        // makes dropping the paths equivalent to replacing the bad first hop.
        val reused = argumentCaptor<Set<Snode>>()
        verify(directory).getGuardSnodes(reused.capture(), any())
        assertThat(reused.firstValue).isEmpty()

        assertThat(pm.paths.value.map { it.first() }).containsExactlyElementsIn(freshGuards)
    }

    @Test
    fun `a rotation that verifies keeps a flaky client off the rebuild path`() = runTest {
        val p1: Path = listOf(snode("a"), snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val spares = (1..12).map { snode("spare$it") }
        val pool = spares + p1 + p2

        val rejecting = pool.toMutableSet()
        val (pm, _, directory, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejecting = rejecting,
        )

        // Two failures, then a rotation that commits, then two more: without the reset on commit
        // these four failures would add up to a rebuild.
        repeat(2) {
            pm.getPath()
            advanceUntilIdle()
        }
        rejecting.clear()
        pm.getPath()
        advanceUntilIdle()
        rejecting += pool
        repeat(2) {
            pm.getPath()
            advanceUntilIdle()
        }
        pmScope.cancel()

        assertThat(pm.paths.value).hasSize(2)
        assertThat(pm.paths.value.map { it.first() }).containsExactly(p1.first(), p2.first())
        verify(directory, never()).getGuardSnodes(any(), any())
    }

    @Test
    fun `a single bad guard escalates to a rebuild`() = runTest {
        val badGuard = snode("a")
        val p1: Path = listOf(badGuard, snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val spares = (1..12).map { snode("spare$it") }
        val pool = spares + p1 + p2
        val freshGuards = setOf(snode("fresh1"), snode("fresh2"))

        // Only the candidate built on the bad guard fails, so the other one verifies and rotation is
        // discarded on the guard-set mismatch rather than on a total failure.
        val (pm, _, directory, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejectingGuards = setOf(badGuard),
            rebuildGuards = freshGuards,
        )

        repeat(3) {
            pm.getPath()
            advanceUntilIdle()
        }

        assertThat(pm.paths.value).isEmpty()

        pm.getPath()
        advanceUntilIdle()
        pmScope.cancel()

        assertThat(pm.paths.value.map { it.first() }).containsExactlyElementsIn(freshGuards)
        assertThat(pm.paths.value.map { it.first() }).doesNotContain(badGuard)
        verify(directory).getGuardSnodes(argThat { isEmpty() }, any())
    }

    @Test
    fun `an offline client keeps its guards however many rotations fail`() = runTest {
        val p1: Path = listOf(snode("a"), snode("b"), snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val spares = (1..12).map { snode("spare$it") }
        val pool = spares + p1 + p2

        val (pm, targeted, directory, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
            rejecting = pool.toSet(),
            networkAvailable = false,
        )

        repeat(6) {
            pm.getPath()
            advanceUntilIdle()
        }
        pmScope.cancel()

        assertThat(targeted).isNotEmpty() // the rotations really ran
        assertThat(pm.paths.value).isEqualTo(listOf(p1, p2))
        verify(directory, never()).getGuardSnodes(any(), any())
    }

    @Test
    fun `a snode is excluded after its address changes`() = runTest {
        val b = snode("b")
        val p1: Path = listOf(snode("a"), b, snode("c"))
        val p2: Path = listOf(snode("d"), snode("e"), snode("f"))
        val pool = p1 + p2

        // Same node, re-fetched after an IP change: equal ed25519 key, different address and port.
        val movedB = Snode(
            address = "https://b-moved.example",
            port = 8443,
            publicKeySet = b.publicKeySet,
        )

        val (pm, _, _, pmScope) = rotatingPathManager(
            pool = pool,
            persistedPaths = listOf(p1, p2),
        )

        // Selection is random among the paths that qualify, so one call cannot tell an exclusion
        // from a coin toss; every one of these must avoid the path the node is really in.
        repeat(10) {
            assertThat(pm.getPath(exclude = movedB)).isEqualTo(p2)
        }
        pmScope.cancel()
    }
}
