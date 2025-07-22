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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
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

    /**
     * Represent the current state of the in-app review flow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @VisibleForTesting
    val state: Flow<State> = commands
        .scan(State.Init) { st, command ->
            when (st) {
                State.Init -> {
                    // Only react to commands if the prompt is currently shown
                    if (manager.shouldShowPrompt.value) {
                        when (command) {
                            UiCommand.PositiveButtonClicked -> State.PositiveFlow
                            UiCommand.NegativeButtonClicked -> State.NegativeFlow
                            UiCommand.CloseButtonClicked -> {
                                manager.onEvent(InAppReviewManager.Event.Dismiss)
                                State.Init
                            }
                        }
                    } else {
                        st
                    }
                }

                State.PositiveFlow -> when (command) {
                    // "Rate App" button clicked
                    UiCommand.PositiveButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.Dismiss)

                        if (runCatching { storeReviewManager.requestReviewFlow() }.isSuccess) {
                            State.Init
                        } else {
                            State.ReviewLimitReached
                        }
                    }

                    // "Not Now"/close button clicked
                    UiCommand.CloseButtonClicked, UiCommand.NegativeButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.ReviewFlowAbandoned)
                        State.Init
                    }
                }

                State.NegativeFlow -> when (command) {
                    // "Open Survey" button clicked
                    UiCommand.PositiveButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.Dismiss)
                        State.ConfirmOpeningSurvey
                    }

                    // "Not Now"/close button clicked
                    UiCommand.CloseButtonClicked, UiCommand.NegativeButtonClicked -> {
                        manager.onEvent(InAppReviewManager.Event.ReviewFlowAbandoned)
                        State.Init
                    }
                }

                State.ConfirmOpeningSurvey -> when (command) {
                    UiCommand.CloseButtonClicked -> State.Init
                    else -> st // Ignore other commands
                }

                State.ReviewLimitReached -> when (command) {
                    UiCommand.CloseButtonClicked -> State.Init
                    else -> st // Ignore other commands
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, started = SharingStarted.Eagerly, initialValue = State.Init)

    val uiState: StateFlow<UiState> =
        combine(state, manager.shouldShowPrompt) { st, shouldShowPrompt ->
            when (st) {
                State.Init -> if (shouldShowPrompt) UiState.Visible(
                    title = "Enjoying Session?",
                    message = "You've been using Session for a little while, how’s it going? We’d really appreciate hearing your thoughts",
                    positiveButtonText = "It's Great ❤\uFE0F",
                    negativeButtonText = "Needs Work \uD83D\uDE15",
                ) else {
                    UiState.Hidden
                }

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

                State.ConfirmOpeningSurvey -> UiState.OpenURLDialog(url = "https://getsession.org/review/survey")
                State.ReviewLimitReached -> UiState.ReviewLimitReachedDialog
            }
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Hidden)

    fun sendUiCommand(command: UiCommand) {
        commands.tryEmit(command)
    }

    @VisibleForTesting
    enum class State {

        /**
         * Initial state. Note that this state is neutral about whether the UI should be shown or not,
         * you should check [InAppReviewManager.shouldShowPrompt] to determine if the UI should be shown.
         */
        Init,
        PositiveFlow,
        NegativeFlow,
        ConfirmOpeningSurvey,
        ReviewLimitReached,
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

        data class OpenURLDialog(
            val url: String,
        ) : UiState

        data object ReviewLimitReachedDialog : UiState
    }
}