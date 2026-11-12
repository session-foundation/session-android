package org.thoughtcrime.securesms.onboarding.loadaccount

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import network.loki.messenger.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.session.libsignal.crypto.MnemonicCodec
import org.session.libsignal.utilities.Hex
import org.thoughtcrime.securesms.MainCoroutineRule
import org.thoughtcrime.securesms.crypto.MnemonicUtilities
import org.thoughtcrime.securesms.util.MockLoggingRule

@RunWith(RobolectricTestRunner::class)
class LoadAccountViewModelTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @get:Rule
    val mockLoggingRule = MockLoggingRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val codec = MnemonicCodec { MnemonicUtilities.loadFileContents(application, it) }

    private val seed16Hex = "0123456789abcdef0123456789abcdef"
    private val seed32Hex = seed16Hex + "fedcba9876543210fedcba9876543210"

    @Test
    fun `25-word phrase shows the generic error and loads nothing`() = runTest {
        val viewModel = LoadAccountViewModel(application)
        val events = mutableListOf<LoadAccountEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onChange(codec.encode(seed32Hex))
        viewModel.onContinue()

        assertEquals(
            application.getString(R.string.recoveryPasswordErrorMessageGeneric),
            viewModel.stateFlow.value.error
        )
        assertTrue(viewModel.stateFlow.value.isTextErrorColor)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `13-word phrase loads its 16-byte seed`() = runTest {
        val viewModel = LoadAccountViewModel(application)
        val events = mutableListOf<LoadAccountEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onChange(codec.encode(seed16Hex))
        viewModel.onContinue()

        assertEquals(null, viewModel.stateFlow.value.error)
        assertEquals(1, events.size)
        assertArrayEquals(Hex.fromStringCondensed(seed16Hex), events.single().mnemonic)
    }

    @Test
    fun `QR code with 32 bytes of hex is refused, not loaded`() = runTest {
        val viewModel = LoadAccountViewModel(application)
        val events = mutableListOf<LoadAccountEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        val qrError = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.qrErrors.first() }

        viewModel.onScanQrCode(seed32Hex)

        assertEquals(application.getString(R.string.qrNotRecoveryPassword), qrError.await())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `QR code with 16 bytes of hex loads its seed`() = runTest {
        val viewModel = LoadAccountViewModel(application)
        val events = mutableListOf<LoadAccountEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onScanQrCode(seed16Hex)

        assertEquals(1, events.size)
        assertArrayEquals(Hex.fromStringCondensed(seed16Hex), events.single().mnemonic)
    }
}
