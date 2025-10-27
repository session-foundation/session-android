package org.thoughtcrime.securesms.pro.subscription

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager.PurchaseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An implementation representing a lack of support for subscription
 */
@Singleton
class NoOpSubscriptionManager @Inject constructor() : SubscriptionManager {
    override val id = "noop"
    override val name = ""
    override val description = ""
    override val iconRes = null

    override val supportsBilling = MutableStateFlow(false)

    override val quickRefundUrl = null

    override fun purchasePlan(subscriptionDuration: ProSubscriptionDuration) {}
    override val availablePlans: List<ProSubscriptionDuration>
        get() = emptyList()

    override val purchaseEvents: SharedFlow<PurchaseEvent> = MutableSharedFlow()

    override suspend fun hasValidSubscription(productId: String): Boolean {
        return false
    }

    override suspend fun isWithinQuickRefundWindow(): Boolean {
        return false
    }
}