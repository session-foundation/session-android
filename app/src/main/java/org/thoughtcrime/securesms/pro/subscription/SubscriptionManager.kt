package org.thoughtcrime.securesms.pro.subscription

import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent

/**
 * Represents the implementation details of a given subscription provider
 */
interface SubscriptionManager: OnAppStartupComponent {
    val id: String
    val displayName: String
    val description: String
    val iconRes: Int?

    val availablePlans: List<ProSubscriptionDuration>

    fun purchasePlan(subscriptionDuration: ProSubscriptionDuration)
}