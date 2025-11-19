package org.thoughtcrime.securesms.pro.subscription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager.PurchaseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An implementation representing a lack of support for subscription
 */
@Singleton
class NoOpSubscriptionManager @Inject constructor(
    proStatusManager: ProStatusManager,
    @param:ManagerScope scope: CoroutineScope,
) : SubscriptionManager(proStatusManager, scope) {
    override val id = "noop"
    override val name = ""
    override val description = ""
    override val iconRes = null

    override val supportsBilling = MutableStateFlow(false)

    override suspend fun purchasePlan(subscriptionDuration: ProSubscriptionDuration): Result<Unit> {
        return Result.success(Unit)
    }
    override val availablePlans: List<ProSubscriptionDuration>
        get() = emptyList()

    override suspend fun hasValidSubscription(): Boolean {
        return false
    }

    override suspend fun getSubscriptionPrices(): List<SubscriptionManager.SubscriptionPricing> {
        return emptyList()
    }
}