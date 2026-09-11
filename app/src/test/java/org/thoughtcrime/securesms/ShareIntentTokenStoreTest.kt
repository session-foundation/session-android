package org.thoughtcrime.securesms

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress

@RunWith(RobolectricTestRunner::class)
class ShareIntentTokenStoreTest {

    private val store = ShareIntentTokenStore()

    private val address = "05${"1".repeat(64)}".toAddress()

    @Test
    fun `resolves a token it minted, with the address it was minted for`() {
        val token = store.mint(address)

        assertThat(store.resolve(token)?.address).isEqualTo(address)
    }

    @Test
    fun `resolves a token minted without an address`() {
        val token = store.mint()

        val minted = store.resolve(token)

        assertThat(minted).isNotNull()
        assertThat(minted?.address).isNull()
    }

    @Test
    fun `does not resolve a token it did not mint`() {
        store.mint(address)

        assertThat(store.resolve("not-a-token-this-store-issued")).isNull()
        assertThat(store.resolve("")).isNull()
        assertThat(store.resolve(null)).isNull()
    }

    @Test
    fun `mints a distinct unguessable token each time`() {
        val tokens = List(500) { store.mint(address) }

        assertThat(tokens.toSet()).hasSize(tokens.size)
        tokens.forEach { assertThat(it.length).isAtLeast(32) }
    }

    @Test
    fun `a token stays resolvable across repeated lookups`() {
        // App lock creates ShareActivity twice for one share - once before it routes to the lock
        // screen, once from the Intent the lock screen replays - and each resolves independently.
        val token = store.mint(address)

        repeat(3) { assertThat(store.resolve(token)?.address).isEqualTo(address) }
    }

    @Test
    fun `retention is bounded, oldest first`() {
        val first = store.mint(address)

        repeat(1_000) { store.mint(address) }

        assertThat(store.resolve(first)).isNull()
    }
}
