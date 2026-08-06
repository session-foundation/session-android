package org.thoughtcrime.securesms.pro.subscription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.pro.ProStatusManager

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

    abstract val availablePlans: List<ProSubscriptionDuration>

    sealed interface PurchaseEvent {
        data object Success : PurchaseEvent
        data object Cancelled : PurchaseEvent
        sealed interface Failed : PurchaseEvent {
            data class GenericError(val errorMessage: String? = null): Failed
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
    fun onPurchaseSuccessful(orderId: String, paymentId: String){
        // The store accepted the payment; the backend redeems it out-of-band (no synchronous add-payment
        // call anymore). Record the purchase as in-flight (synced) and kick off the proof-redemption poll.
        // orderId/paymentId are no longer needed by the backend — kept only for the callback signature.
        scope.launch {
            try {
                proStatusManager.onPurchaseInFlight()
                _purchaseEvents.emit(PurchaseEvent.Success)
            } catch (e: Exception) {
                Log.e("SubscriptionManager", "Failed to record purchase in flight", e)
                _purchaseEvents.emit(PurchaseEvent.Failed.GenericError())
            }
        }
    }

    data class SubscriptionPricing(
        val subscriptionDuration: ProSubscriptionDuration,
        val priceAmountMicros: Long,
        val priceCurrencyCode: String,
        val billingPeriodIso: String,
        val formattedTotal: String,
    )
}

