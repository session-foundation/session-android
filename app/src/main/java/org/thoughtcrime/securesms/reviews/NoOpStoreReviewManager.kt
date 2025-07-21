package org.thoughtcrime.securesms.reviews

import javax.inject.Inject

class NoOpStoreReviewManager @Inject constructor() : StoreReviewManager {
    override val supportsReviewFlow: Boolean
        get() = false

    override suspend fun requestReviewFlow() {
        // No operation, this is a no-op implementation
    }
}
