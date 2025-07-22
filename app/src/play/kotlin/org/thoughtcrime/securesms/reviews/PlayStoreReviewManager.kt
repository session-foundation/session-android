package org.thoughtcrime.securesms.reviews

import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayStoreReviewManager @Inject constructor(
    private val manager: ReviewManager
) : StoreReviewManager {

    override val supportsReviewFlow: Boolean
        get() = true

    override suspend fun requestReviewFlow() {
        manager.requestReview()
    }
}