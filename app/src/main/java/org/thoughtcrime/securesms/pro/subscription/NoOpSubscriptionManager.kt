package org.thoughtcrime.securesms.pro.subscription

import javax.inject.Inject

/**
 * An implementation representing a lack of support for subscription
 */
class NoOpSubscriptionManager @Inject constructor() : SubscriptionManager {
    override val id = "noop"
    override val displayName = ""
    override val description = ""
    override val iconRes = null

    override fun purchasePlan(subscriptionDuration: ProSubscriptionDuration) {}
    override val availablePlans: List<ProSubscriptionDuration>
        get() = emptyList()

    //todo PRO test out build type with no subscription providers available - What do we show on the Pro Settings page?
}