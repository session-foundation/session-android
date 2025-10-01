package org.thoughtcrime.securesms.pro.subscription

import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.time.Instant

/**
 * Represents the implementation details of a given subscription provider
 */
interface SubscriptionManager: OnAppStartupComponent {
    val id: String
    val displayName: String
    val description: String
    val platform: String
    val iconRes: Int?

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
}