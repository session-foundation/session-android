package org.thoughtcrime.securesms.reviews

import android.app.Application
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class PlayStoreReviewManager @Inject constructor(
    private val manager: ReviewManager,
    private val currentActivityObserver: CurrentActivityObserver,
    private val application: Application,
) : StoreReviewManager {

    override val storeName: String by lazy {
        val pm = application.packageManager
        runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo("com.android.vending", 0))
        }.getOrNull()?.toString() ?: "Google Play Store"
    }

    override val supportsReviewFlow: Boolean
        get() = true

    override suspend fun requestReviewFlow() {
        val requestedOnActivity = currentActivityObserver.currentActivity.value
        val activity = requireNotNull(requestedOnActivity) {
            "Cannot request review flow without a current activity."
        }

        val info = manager.requestReview()
        manager.launchReview(activity, info)

        val hasLaunchedSomething = withTimeoutOrNull(500.milliseconds) {
            currentActivityObserver.currentActivity.first { it != requestedOnActivity }
        } != null

        require(hasLaunchedSomething) {
            "Failed to launch review flow"
        }
    }
}