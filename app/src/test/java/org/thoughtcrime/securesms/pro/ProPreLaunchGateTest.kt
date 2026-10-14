package org.thoughtcrime.securesms.pro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.auth.LoginStateRepository

/**
 * Flag off, this account gets no Pro and nothing is restricted for lacking it; flag on, the Pro rules apply.
 *
 * The character limits are not covered here: they are libsession constants, and this JVM has no native
 * library to read them from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProPreLaunchGateTest {

    private val configFactory: ConfigFactoryProtocol = mock()
    private val configFactoryLazy: dagger.Lazy<ConfigFactoryProtocol> = mock {
        on { get() } doReturn configFactory
    }

    private fun manager(postPro: Boolean): ProStatusManager {
        val prefs: TextSecurePreferences = mock {
            on { forcePostPro() } doReturn postPro
            on { watchPostProStatus() } doReturn MutableStateFlow(postPro)
        }
        val loginState: LoginStateRepository = mock {
            on { flowWithLoggedInState<ProDataState>(any()) } doReturn emptyFlow()
        }

        return ProStatusManager(
            application = mock<Application>(),
            prefs = prefs,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            serverApiExecutor = mock(),
            backendConfig = mock(),
            loginState = loginState,
            proDatabase = mock(),
            snodeClock = mock(),
            proStatusRepository = mock(),
            configFactory = configFactoryLazy,
            appVisibilityManager = mock(),
        )
    }

    private fun nonProContact(): Recipient = mock {
        on { isCommunityRecipient } doReturn false
        on { isPro } doReturn false
    }

    @Test
    fun `pre-launch there is no pinned conversation limit`() {
        assertEquals(Int.MAX_VALUE, manager(postPro = false).getPinnedConversationLimit(isPro = false))
    }

    @Test
    fun `post-launch a non-Pro user is held to the pinned conversation limit`() {
        assertEquals(
            ProStatusManager.MAX_PIN_REGULAR,
            manager(postPro = true).getPinnedConversationLimit(isPro = false)
        )
    }

    @Test
    fun `pre-launch no avatar is frozen`() {
        assertFalse(manager(postPro = false).freezeFrameForUser(nonProContact()))
    }

    @Test
    fun `post-launch a non-Pro avatar is frozen`() {
        assertTrue(manager(postPro = true).freezeFrameForUser(nonProContact()))
    }

    @Test
    fun `pre-launch a synced proof is never read, so nothing is attached or declared`() {
        // Config is where another device's purchase lands, so not reading it is the whole point
        val manager = manager(postPro = false)
        val message = VisibleMessage(text = "hello")

        assertNull(manager.currentUserProProofForAccess())
        manager.addProFeatures(message)

        assertTrue(message.proFeatures.isEmpty())
        verify(configFactoryLazy, never()).get()
    }

    @Test
    fun `post-launch the proof is read from config`() {
        // The control for the test above: without it, "never read" would also pass if nothing ever read it.
        // The mocked config has no user configs to hand back, so the read itself throws; reaching it is enough.
        runCatching { manager(postPro = true).currentUserProProofForAccess() }

        verify(configFactoryLazy).get()
    }
}
