package org.thoughtcrime.securesms.pro.subscription

import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.time.Instant

/**
 * Represents the implementation details of a given subscription provider
 */
interface SubscriptionManager: OnAppStartupComponent {
    val id: String
    val description: String
    val iconRes: Int?

    val details: SubscriptionDetails

    // Optional. Some store can have a platform specific refund window and url
    val quickRefundExpiry: Instant?
    val quickRefundUrl: String?

    val availablePlans: List<ProSubscriptionDuration>

    fun purchasePlan(subscriptionDuration: ProSubscriptionDuration)

    /**
     * Returns true if a provider has a non null [quickRefundExpiry] and the current time is within that window
     */
    fun isWithinQuickRefundWindow(): Boolean {
        return quickRefundExpiry != null && Instant.now().isBefore(quickRefundExpiry)
    }

    /**
     * Checks whether there is a valid subscription for the given product id for the current user within this subscriber's billing API
     */
    fun hasValidSubscription(productId: String): Boolean
}

data class SubscriptionDetails(
    val device: String,
    val store: String,
    val platform: String,
    val platformAccount: String,
    val subscriptionUrl: String,
    val refundUrl: String,
)