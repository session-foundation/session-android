package org.thoughtcrime.securesms.reviews.ui

import android.content.Context
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
import org.mockito.kotlin.times
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
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            manager.onEvent(InAppReviewManager.Event.DonateButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.StartPrompt, awaitItem())

            // Click on positive button -- should show the positive prompt
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.PositivePrompt, awaitItem())

            // Click on the positive button again - should request review flow
            verifyBlocking(storeReviewManager, never()) { requestReviewFlow() }
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            // We should have a hidden state at the end
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())
            verifyBlocking(storeReviewManager, times(1)) { requestReviewFlow() }
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
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Click on donate button - should show the prompt
            manager.onEvent(InAppReviewManager.Event.DonateButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.StartPrompt, awaitItem())

            // Click on positive button - should show the positive prompt
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.PositivePrompt, awaitItem())

            // Click on the positive button again - should request review flow
            verifyBlocking(storeReviewManager, never()) { requestReviewFlow() }
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.ReviewLimitReached, awaitItem())
            verifyBlocking(storeReviewManager) { requestReviewFlow() }

            // Dismiss the dialog
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.CloseButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())
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
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Click on donate button - should show the prompt
            manager.onEvent(InAppReviewManager.Event.PathScreenVisited)
            assertEquals(InAppReviewViewModel.UiState.StartPrompt, awaitItem())

            // Click on negative button - should have negative prompt
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.NegativePrompt, awaitItem())

            // Click on the positive button - should open survey
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.ConfirmOpeningSurvey, awaitItem())

            // Dismiss the dialog
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.CloseButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())
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
        )

        vm.uiState.test {
            // Initial state
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Change theme - should show the prompt
            manager.onEvent(InAppReviewManager.Event.ThemeChanged)
            assertEquals(InAppReviewViewModel.UiState.StartPrompt, awaitItem())

            // Click on negative button - should have negative prompt
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.NegativePrompt, awaitItem())

            // Click on negative button again - should dismiss the prompt
            vm.sendUiCommand(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            assertEquals(InAppReviewViewModel.UiState.Hidden, awaitItem())

            // Wait for the state to reset
            advanceTimeBy(InAppReviewManager.REVIEW_REQUEST_DISMISS_DELAY)

            // Now the prompt should reappear
            assertEquals(InAppReviewViewModel.UiState.StartPrompt, awaitItem())
        }
    }
}