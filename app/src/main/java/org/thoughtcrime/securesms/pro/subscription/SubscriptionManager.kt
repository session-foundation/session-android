package org.thoughtcrime.securesms.pro.subscription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.pro.ProStatusManager
import java.time.Instant

/**
 * Represents the implementation details of a given subscription provider
 */
abstract class SubscriptionManager(
    protected val proStatusManager: ProStatusManager,
    @param:ManagerScope protected val scope: CoroutineScope,
): OnAppStartupComponent {
    abstract val id: String
    abstract val name: String
    abstract val description: String
    abstract val iconRes: Int?

    abstract val supportsBilling: StateFlow<Boolean>

    // Optional. Some store can have a platform specific refund window and url
    abstract val quickRefundUrl: String?

    abstract val availablePlans: List<ProSubscriptionDuration>

    sealed interface PurchaseEvent {
        data object Success : PurchaseEvent
        data object Cancelled : PurchaseEvent
        sealed interface Failed : PurchaseEvent {
            data class GenericError(val errorMessage: String? = null): Failed
            data object ServerError : Failed
        }
    }

    // purchase events
    protected val _purchaseEvents = MutableSharedFlow<PurchaseEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val purchaseEvents: SharedFlow<PurchaseEvent> = _purchaseEvents.asSharedFlow()

    abstract suspend fun purchasePlan(subscriptionDuration: ProSubscriptionDuration): Result<Unit>

    /**
     * Returns true if a provider has a quick refunds and the current time since purchase is within that window
     */
    abstract suspend fun isWithinQuickRefundWindow(): Boolean

    /**
     * Checks whether there is a valid subscription for the current user within this subscriber's billing API
     */
    abstract suspend fun hasValidSubscription(): Boolean

    /**
     * Gets a list of pricing for the subscriptions
     * @throws Exception in case of errors fetching prices
     */
    @Throws(Exception::class)
    abstract suspend fun getSubscriptionPrices(): List<SubscriptionPricing>

    /**
     * Function called when a purchased has been made successfully from the subscription api
     */
    protected fun onPurchaseSuccessful(orderId: String, paymentId: String){
        // we need to tie our purchase with the back end
        scope.launch {
            try {
                proStatusManager.appProPaymentToBackend(orderId, paymentId)
                _purchaseEvents.emit(PurchaseEvent.Success)
            } catch (e: Exception) {
                when (e) {
                    is PaymentServerException -> _purchaseEvents.emit(PurchaseEvent.Failed.ServerError)
                    else -> _purchaseEvents.emit(PurchaseEvent.Failed.GenericError())
                }
            }
        }
    }

    class PaymentServerException: Exception()

    data class SubscriptionPricing(
        val subscriptionDuration: ProSubscriptionDuration,
        val priceAmountMicros: Long,
        val priceCurrencyCode: String,
        val billingPeriodIso: String,
        val formattedTotal: String,
    )
}

