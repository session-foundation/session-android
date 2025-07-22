package org.thoughtcrime.securesms.reviews.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.Flow
import org.thoughtcrime.securesms.ui.AlertDialogContent
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString

@Composable
fun InAppReview(
    uiState: InAppReviewViewModel.UiState,
    uiEvents: Flow<InAppReviewViewModel.UiEvent>,
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit,
) {
    LaunchedEffect(uiEvents) {
        uiEvents.collect { event ->
            when (event) {
                InAppReviewViewModel.UiEvent.OpenReviewLimitReachedDialog -> {
                    //TODO: Show a dialog indicating that the review limit has been reached
                }
                is InAppReviewViewModel.UiEvent.OpenURL -> {
                    //TODO: Open the URL
                }
            }
        }
    }

    AnimatedContent(uiState, contentKey = { it.javaClass }) { st ->
        if (st is InAppReviewViewModel.UiState.Visible) {
            AlertDialogContent(
                onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
                title = AnnotatedString(st.title),
                text = AnnotatedString(st.message),
                buttons = listOf(
                    DialogButtonData(text = GetString.FromString(st.positiveButtonText)) {
                        sendCommands(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
                    },
                    DialogButtonData(text = GetString.FromString(st.negativeButtonText)) {
                        sendCommands(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
                    },
                )
            )
        }
    }
}
