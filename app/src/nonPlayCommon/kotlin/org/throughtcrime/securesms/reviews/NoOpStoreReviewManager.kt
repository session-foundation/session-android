package org.throughtcrime.securesms.reviews

import org.thoughtcrime.securesms.reviews.StoreReviewManager
import javax.inject.Inject

class NoOpStoreReviewManager @Inject constructor() : StoreReviewManager {
    override val storeName: String
        get() = ""
    override val supportsReviewFlow: Boolean
        get() = false

    override suspend fun requestReviewFlow() {
        // No operation, this is a no-op implementation
    }
}