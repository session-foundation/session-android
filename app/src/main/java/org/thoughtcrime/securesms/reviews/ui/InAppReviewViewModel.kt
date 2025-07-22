package org.thoughtcrime.securesms.reviews.ui

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.reviews.StoreReviewManager
import javax.inject.Inject

@HiltViewModel
class InAppReviewViewModel @Inject constructor(
    private val manager: InAppReviewManager,
    private val storeReviewManager: StoreReviewManager,
) : ViewModel() {
    private val commands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 1)
    private val mutableEvents = MutableSharedFlow<UiEvent>()

    val events: Flow<UiEvent> get() = mutableEvents

    @OptIn(ExperimentalCoroutinesApi::class)
    @VisibleForTesting
    val state: Flow<State> = manager.shouldShowPrompt
        .flatMapLatest { shouldShow ->
            val initialState = if (shouldShow) State.Home else State.Hidden

            commands
                .scan(initialState) { st, command ->
                    when (st) {
                        State.Home -> when (command) {
                            UiCommand.PositiveButtonClicked -> State.PositiveFlow
                            UiCommand.NegativeButtonClicked -> State.NegativeFlow
                            UiCommand.CloseButtonClicked -> {
                                manager.onEvent(InAppReviewManager.Event.Dismiss)
                                State.Hidden
                            }
                        }

                        State.PositiveFlow -> when (command) {
                            // "Rate App" button clicked
                            UiCommand.PositiveButtonClicked -> {
                                if (runCatching { storeReviewManager.requestReviewFlow() }.isSuccess) {
                                    manager.onEvent(InAppReviewManager.Event.Dismiss)
                                } else {
                                    mutableEvents.emit(UiEvent.OpenReviewLimitReachedDialog)
                                }

                                State.Hidden
                            }

                            // "Not Now"/close button clicked
                            UiCommand.CloseButtonClicked, UiCommand.NegativeButtonClicked -> {
                                manager.onEvent(InAppReviewManager.Event.ReviewFlowAbandoned)
                                State.Hidden
                            }
                        }

                        State.NegativeFlow -> when (command) {
                            // "Open Survey" button clicked
                            UiCommand.PositiveButtonClicked -> {
                                manager.onEvent(InAppReviewManager.Event.Dismiss)
                                mutableEvents.emit(UiEvent.OpenURL("https://getsession.org/review/survey"))
                                State.Hidden
                            }

                            // "Not Now"/close button clicked
                            UiCommand.CloseButtonClicked, UiCommand.NegativeButtonClicked -> {
                                manager.onEvent(InAppReviewManager.Event.ReviewFlowAbandoned)
                                State.Hidden
                            }
                        }

                        // If we are in the Hidden state, we ignore any commands
                        State.Hidden -> State.Hidden
                    }
                }
        }
        .shareIn(viewModelScope, started = SharingStarted.Eagerly)

    val uiState: StateFlow<UiState> = state
        .map { st ->
            when (st) {
                State.Hidden -> UiState.Hidden
                State.Home -> UiState.Visible(
                    title = "Enjoying Session?",
                    message = "You've been using Session for a little while, how’s it going? We’d really appreciate hearing your thoughts",
                    positiveButtonText = "It's Great ❤\uFE0F",
                    negativeButtonText = "Needs Work \uD83D\uDE15",
                )
                State.PositiveFlow -> UiState.Visible(
                    title = "Rate Session?",
                    message = "We're glad you're enjoying Session, if you have a moment, rating us in the Google Play helps others discover private, secure messaging!",
                    positiveButtonText = "Rate App",
                    negativeButtonText = "Not Now",
                )
                State.NegativeFlow -> UiState.Visible(
                    title = "Give Feedback?",
                    message = "Sorry to hear your Session experience hasn’t been ideal. We'd be grateful if you could take a moment to share your thoughts in a brief survey",
                    positiveButtonText = "Open Survey",
                    negativeButtonText = "Not Now",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Hidden)

    fun sendUiCommand(command: UiCommand) {
        commands.tryEmit(command)
    }

    @VisibleForTesting
    enum class State {
        Hidden,
        Home,
        PositiveFlow,
        NegativeFlow,
    }

    enum class UiCommand {
        PositiveButtonClicked,
        NegativeButtonClicked,
        CloseButtonClicked,
    }

    sealed interface UiState {
        data object Hidden : UiState

        data class Visible(
            val title: String,
            val message: String,
            val positiveButtonText: String,
            val negativeButtonText: String,
        ) : UiState
    }

    sealed interface UiEvent {
        data class OpenURL(val url: String) : UiEvent
        data object OpenReviewLimitReachedDialog : UiEvent
    }
}