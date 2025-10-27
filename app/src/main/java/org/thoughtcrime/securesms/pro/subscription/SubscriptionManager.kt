package org.thoughtcrime.securesms.pro.subscription

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.time.Instant

/**
 * Represents the implementation details of a given subscription provider
 */
interface SubscriptionManager: OnAppStartupComponent {
    val id: String
    val name: String
    val description: String
    val iconRes: Int?

    val supportsBilling: StateFlow<Boolean>

    // Optional. Some store can have a platform specific refund window and url
    val quickRefundUrl: String?

    val availablePlans: List<ProSubscriptionDuration>

    sealed interface PurchaseEvent {
        data object Success : PurchaseEvent
        data object Cancelled : PurchaseEvent
        data class Failed(val errorMessage: String? = null) : PurchaseEvent
    }

    // purchase events
    val purchaseEvents: SharedFlow<PurchaseEvent>

    suspend fun purchasePlan(subscriptionDuration: ProSubscriptionDuration): Result<Unit>

    /**
     * Returns true if a provider has a quick refunds and the current time since purchase is within that window
     */
    suspend fun isWithinQuickRefundWindow(): Boolean

    /**
     * Checks whether there is a valid subscription for the given product id for the current user within this subscriber's billing API
     */
    suspend fun hasValidSubscription(productId: String): Boolean
}

