package org.thoughtcrime.securesms.reviews.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.STORE_VARIANT_KEY
import org.session.libsession.utilities.TranslatableText
import javax.inject.Inject

@HiltViewModel
class InAppReviewViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
                    title = TranslatableText(R.string.enjoyingSession, APP_NAME_KEY to context.getString(R.string.app_name)),
                    message = TranslatableText(R.string.enjoyingSessionDescription, APP_NAME_KEY to context.getString(R.string.app_name)),
                    positiveButtonText = TranslatableText(R.string.enjoyingSessionButtonPositive, EMOJI_KEY to "❤\uFE0F"),
                    negativeButtonText = TranslatableText(R.string.enjoyingSessionButtonNegative, EMOJI_KEY to "\uD83D\uDE15"),
                ) else {
                    UiState.Hidden
                }

                State.PositiveFlow -> UiState.Visible(
                    title = TranslatableText(R.string.rateSession, APP_NAME_KEY to context.getString(R.string.app_name)),
                    message = TranslatableText(
                        R.string.rateSessionModalDescription,
                        APP_NAME_KEY to context.getString(R.string.app_name),
                        STORE_VARIANT_KEY to storeReviewManager.storeName
                    ),
                    positiveButtonText = TranslatableText(R.string.rateSessionApp),
                    negativeButtonText = TranslatableText(R.string.notNow),
                )

                State.NegativeFlow -> UiState.Visible(
                    title = TranslatableText(R.string.giveFeedback, APP_NAME_KEY to context.getString(R.string.app_name)),
                    message = TranslatableText(
                        R.string.giveFeedbackDescription,
                        APP_NAME_KEY to context.getString(R.string.app_name),
                    ),
                    positiveButtonText = TranslatableText(R.string.openSurvey),
                    negativeButtonText = TranslatableText(R.string.notNow),
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
            val title: TranslatableText,
            val message: TranslatableText,
            val positiveButtonText: TranslatableText,
            val negativeButtonText: TranslatableText,
        ) : UiState

        data class OpenURLDialog(
            val url: String,
        ) : UiState

        data object ReviewLimitReachedDialog : UiState
    }
}