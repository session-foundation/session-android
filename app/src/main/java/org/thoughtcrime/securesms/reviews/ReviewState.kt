package org.thoughtcrime.securesms.reviews

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator(ReviewState.DISCRIMINATOR)
sealed interface ReviewState {

    /**
     * Indicate we are waiting for the user to trigger the review request.
     */
    @Serializable
    @SerialName("waiting")
    data class WaitingForTrigger(
        // Whether the app was updated over an old version since the review request feature was added.
        val appUpdated: Boolean
    ) : ReviewState

    /**
     * Indicates that we should be showing the review prompt right now.
     */
    @Serializable
    @SerialName("showing")
    data object ShowingReviewRequest : ReviewState

    /**
     * Indicates that we have shown the review request but the user has abandoned it mid-flow,
     * we'll then try to prompt them again later.
     *
     * When at this state, we should compare the current time with the `until` time to determine
     * whether we are should show the review request now.
     */
    @Serializable
    @SerialName("dismissed_until")
    data class DismissedUntil(
        @SerialName("until")
        val untilTimestampMills: Long,
    ) : ReviewState

    /**
     * Indicates that the user has dismissed the review request permanently, or we have
     * determined that the user should not be prompted again.
     */
    @Serializable
    @SerialName("dismissed_forever")
    data object DismissedForever : ReviewState

    companion object {
        const val DISCRIMINATOR = "type"
    }
}