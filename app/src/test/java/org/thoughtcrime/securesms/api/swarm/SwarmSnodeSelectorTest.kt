package org.thoughtcrime.securesms.api.swarm

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsignal.utilities.Snode

class SwarmSnodeSelectorTest {
    private lateinit var swarmDirectory: SwarmDirectory
    private lateinit var selector: SwarmSnodeSelector

    private val swarmPool = List(5) {
        Snode(
            url = "https://snode$it.example".toHttpUrl(),
            publicKeySet = Snode.KeySet("ed25519Key$it", "x25519Key$it")
        )
    }

    @Before
    fun setUp() {
        swarmDirectory = mockk(relaxed = true) {
            coEvery { getSwarm(any()) } returns swarmPool
        }

        selector = SwarmSnodeSelector(swarmDirectory)
    }

    @Test
    fun `should select different node in a swarm in random order`() = runTest {
        val firstSelectedNodes = List(swarmPool.size) {
            selector.selectSnode("swarmPubKey")
        }

        val secondSelectedNodes = List(swarmPool.size) {
            selector.selectSnode("swarmPubKey")
        }

        // Validate that the selected nodes are from the swarm pool but in different order
        assertNotEquals(firstSelectedNodes, swarmPool)
        assertEquals(firstSelectedNodes.toHashSet(), swarmPool.toHashSet())

        // Validate that two selection rounds produce different orders
        assertNotEquals(firstSelectedNodes, secondSelectedNodes)
        assertEquals(firstSelectedNodes.toHashSet(), secondSelectedNodes.toHashSet())
    }
}