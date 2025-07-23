package org.thoughtcrime.securesms.reviews.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.reviews.StoreReviewManager
import javax.inject.Inject

private const val TAG = "InAppReviewViewModel"

@HiltViewModel
class InAppReviewViewModel @Inject constructor(
    private val manager: InAppReviewManager,
    private val storeReviewManager: StoreReviewManager,
) : ViewModel() {
    private val commands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 1)

    /**
     * Represent the current state of the in-app review flow.
     *
     * This flow is done by advancing the state machine based on the events emitted by both
     * UI and the [InAppReviewManager].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<UiState> = merge(commands, manager.shouldShowPrompt.filter { it }.map { ShowPrompt })
        .scan(UiState.Hidden) { st, event ->
            Log.d(TAG, "Received $event, current state = $st")
            when (st) {
                UiState.Hidden -> when (event) {
                    ShowPrompt -> UiState.StartPrompt
                    else -> st // Ignore other events
                }

                UiState.StartPrompt -> {
                    when (event) {
                        UiCommand.PositiveButtonClicked -> UiState.PositivePrompt
                        UiCommand.NegativeButtonClicked -> UiState.NegativePrompt
                        UiCommand.CloseButtonClicked -> {
                            manager.onEvent(InAppReviewManager.Event.Dismiss)
                            UiState.Hidden
                        }
                        else -> st // Ignore other event
                    }
                }

                UiState.PositivePrompt -> when (event) {
                    // "Rate App" button clicked
                    UiCommand.PositiveButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.Dismiss)

                        if (runCatching { storeReviewManager.requestReviewFlow() }.isSuccess) {
                            UiState.Hidden
                        } else {
                            UiState.ReviewLimitReached
                        }
                    }

                    // "Not Now"/close button clicked
                    UiCommand.CloseButtonClicked, UiCommand.NegativeButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.ReviewFlowAbandoned)
                        UiState.Hidden
                    }

                    else -> st // Ignore other events
                }

                UiState.NegativePrompt -> when (event) {
                    // "Open Survey" button clicked
                    UiCommand.PositiveButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.Dismiss)
                        UiState.ConfirmOpeningSurvey
                    }

                    // "Not Now"/close button clicked
                    UiCommand.CloseButtonClicked, UiCommand.NegativeButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.Dismiss)
                        UiState.Hidden
                    }

                    else -> st // Ignore other events
                }

                UiState.ConfirmOpeningSurvey -> when (event) {
                    UiCommand.CloseButtonClicked -> UiState.Hidden
                    else -> st // Ignore other commands
                }

                UiState.ReviewLimitReached -> when (event) {
                    UiCommand.CloseButtonClicked -> UiState.Hidden
                    else -> st // Ignore other commands
                }
            }
        }
        .distinctUntilChanged()
        .onEach { Log.d(TAG, "New state $it") }
        .stateIn(viewModelScope, started = SharingStarted.Eagerly, initialValue = UiState.Hidden)

    fun sendUiCommand(command: UiCommand) {
        commands.tryEmit(command)
    }

    enum class UiState {
        StartPrompt,
        PositivePrompt,
        NegativePrompt,
        ConfirmOpeningSurvey,
        ReviewLimitReached,
        Hidden,
    }

    /**
     * Represents the event that can occur in the in-app review flow.
     */
    private sealed interface Event

    enum class UiCommand : Event {
        PositiveButtonClicked,
        NegativeButtonClicked,
        CloseButtonClicked,
    }

    /**
     * Triggered when the [InAppReviewManager] determines that we should show the review prompt.
     *
     * Whether we actually show the prompt will be further controlled by us.
     */
    private data object ShowPrompt : Event
}