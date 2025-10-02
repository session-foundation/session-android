package org.thoughtcrime.securesms.pro.subscription

import javax.inject.Inject

/**
 * An implementation representing a lack of support for subscription
 */
class NoOpSubscriptionManager @Inject constructor() : SubscriptionManager {
    override val id = "noop"
    override val description = ""
    override val iconRes = null

    override val details = SubscriptionDetails(
        device = "",
        store = "",
        platform = "",
        platformAccount = "",
        subscriptionUrl = "",
        refundUrl = "",
    )

    override val quickRefundExpiry = null
    override val quickRefundUrl = null

    override fun purchasePlan(subscriptionDuration: ProSubscriptionDuration) {}
    override val availablePlans: List<ProSubscriptionDuration>
        get() = emptyList()

    override fun hasValidSubscription(productId: String): Boolean {
        return false
    }

    //todo PRO test out build type with no subscription providers available - What do we show on the Pro Settings page?
}