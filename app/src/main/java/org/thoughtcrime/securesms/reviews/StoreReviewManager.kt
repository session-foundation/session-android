package org.thoughtcrime.securesms.reviews

/**
 * Interface for managing review flows using a particular app store's API.
 */
interface StoreReviewManager {
    val supportsReviewFlow: Boolean
    val storeName: String
    suspend fun requestReviewFlow()
}
