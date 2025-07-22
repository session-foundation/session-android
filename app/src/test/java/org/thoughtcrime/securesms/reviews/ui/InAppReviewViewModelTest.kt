package org.thoughtcrime.securesms.reviews.ui

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.thoughtcrime.securesms.BaseViewModelTest
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.reviews.StoreReviewManager
import org.thoughtcrime.securesms.reviews.createManager

@RunWith(JUnit4::class)
class InAppReviewViewModelTest : BaseViewModelTest() {

    lateinit var context: Context

    @Before
    fun setUp() {
        context = mock {
            on { getString(any()) } doReturn "Mocked String"
        }
    }

    @Test
    fun `should go through store flow`() = runTest {
        val manager = createManager(isFreshInstall = false, supportInAppReviewFlow = true)
        val storeReviewManager = mock<StoreReviewManager> {
            onBlocking { requestReviewFlow() }
                .thenReturn(Unit) // Simulate successful request
        }

        val vm = InAppReviewViewModel(
            manager = manager,
            storeReviewManager = storeReviewManager,
            context = context,
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Click on donate button - should show the prompt
            manager.onEvent(InAppReviewManager.Event.DonateButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on positive button - should have another visible state
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on the positive button again - should request review flow
            verifyBlocking(storeReviewManager, never()) { requestReviewFlow() }
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            verifyBlocking(storeReviewManager) { requestReviewFlow() }
            // We should have a hidden state at the end
            while (awaitItem() != InAppReviewViewModel.UiState.Hidden) {}
        }
    }

    @Test
    fun `should show limit reached when errors in store flow`() = runTest {
        val manager = createManager(isFreshInstall = false, supportInAppReviewFlow = true)
        val storeReviewManager = mock<StoreReviewManager> {
            onBlocking { requestReviewFlow() }
                .thenThrow(RuntimeException())
        }

        val vm = InAppReviewViewModel(
            manager = manager,
            storeReviewManager = storeReviewManager,
            context = context,
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Click on donate button - should show the prompt
            manager.onEvent(InAppReviewManager.Event.DonateButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on positive button - should have another visible state
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on the positive button again - should request review flow
            verifyBlocking(storeReviewManager, never()) { requestReviewFlow() }
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            verifyBlocking(storeReviewManager) { requestReviewFlow() }
            // We should have a hidden state at the end
            while (awaitItem() != InAppReviewViewModel.UiState.ReviewLimitReachedDialog) {}
        }
    }

    @Test
    fun `should go through survey flow`() = runTest {
        val manager = createManager(isFreshInstall = true, supportInAppReviewFlow = true)
        val storeReviewManager = mock<StoreReviewManager> {
            onBlocking { requestReviewFlow() }
                .thenReturn(Unit) // Simulate successful request
        }

        val vm = InAppReviewViewModel(
            manager = manager,
            storeReviewManager = storeReviewManager,
            context = context,
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Click on donate button - should show the prompt
            manager.onEvent(InAppReviewManager.Event.PathScreenVisited)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on negative button - should have another visible state
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on the positive button - should open survey
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.OpenURLDialog)
        }
    }

    @Test
    fun `should reappear after dismissing mid-flow`() = runTest {
        val manager = createManager(isFreshInstall = true, supportInAppReviewFlow = true)
        val storeReviewManager = mock<StoreReviewManager> {
            onBlocking { requestReviewFlow() }
                .thenReturn(Unit) // Simulate successful request
        }

        val vm = InAppReviewViewModel(
            manager = manager,
            storeReviewManager = storeReviewManager,
            context = context,
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Change theme - should show the prompt
            manager.onEvent(InAppReviewManager.Event.ThemeChanged)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on negative button - should have another visible state
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)

            // Click on negative button again - should dismiss the prompt
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            while (awaitItem() != InAppReviewViewModel.UiState.Hidden) {}

            // Wait for the state to reset
            advanceTimeBy(InAppReviewManager.REVIEW_REQUEST_DISMISS_DELAY)

            // Now the prompt should reappear
            assert(awaitItem() is InAppReviewViewModel.UiState.Visible)
        }
    }
}