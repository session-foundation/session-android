package org.thoughtcrime.securesms.reviews

interface StoreReviewManager {
    val supportsReviewFlow: Boolean
    suspend fun requestReviewFlow()
}
