package org.thoughtcrime.securesms.reviews

import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class PlayStoreReviewManager @Inject constructor(
    private val manager: ReviewManager,
    private val currentActivityObserver: CurrentActivityObserver,
) : StoreReviewManager {

    override val supportsReviewFlow: Boolean
        get() = true

    override suspend fun requestReviewFlow() {
        val activity = requireNotNull(currentActivityObserver.currentActivity.value) {
            "Cannot request review flow without a current activity."
        }

        val info = manager.requestReview()
        manager.launchReview(activity, info)

        val hasLaunchedSomething = withTimeoutOrNull(1.seconds) {
            currentActivityObserver.currentActivity.first { it == null }
        } != null

        require(hasLaunchedSomething) {
            "Failed to launch review flow"
        }
    }
}