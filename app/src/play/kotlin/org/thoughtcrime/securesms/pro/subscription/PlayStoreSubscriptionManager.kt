package org.thoughtcrime.securesms.pro.subscription

import android.app.Application
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject

/**
 * The Google Play Store implementation of our subscription manager
 */
class PlayStoreSubscriptionManager @Inject constructor(
    private val application: Application,
    @param:ManagerScope private val scope: CoroutineScope,
    private val currentActivityObserver: CurrentActivityObserver,
) : SubscriptionManager {
    override val id = "google_play_store"
    override val displayName = ""
    override val description = ""
    override val iconRes = null

    private val billingClient by lazy {
        BillingClient.newBuilder(application)
            .setListener { result, purchases ->
                Log.d(TAG, "onPurchasesUpdated: $result, $purchases")
            }
            .enableAutoServiceReconnection()
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()
    }

    override val availablePlans: List<ProSubscriptionDuration> =
        ProSubscriptionDuration.entries.toList()

    override fun purchasePlan(subscriptionDuration: ProSubscriptionDuration) {
        scope.launch {
            try {
                val activity = checkNotNull(currentActivityObserver.currentActivity.value) {
                    "No current activity available to launch the billing flow"
                }

                val result = billingClient.queryProductDetails(
                    QueryProductDetailsParams.newBuilder()
                        .setProductList(
                            listOf(
                                QueryProductDetailsParams.Product.newBuilder()
                                    .setProductId("session_pro")
                                    .setProductType(BillingClient.ProductType.SUBS)
                                    .build()
                            )
                        )
                        .build()
                )

                check(result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    "Failed to query product details. Reason: ${result.billingResult}"
                }

                val productDetails = checkNotNull(result.productDetailsList?.firstOrNull()) {
                    "Unable to get the product: product for given id is null"
                }

                val planId = subscriptionDuration.planId

                val offerDetails = checkNotNull(productDetails.subscriptionOfferDetails
                    ?.firstOrNull { it.basePlanId == planId }) {
                        "Unable to find a plan with id $planId"
                    }

                val billingResult = billingClient.launchBillingFlow(
                    activity, BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(productDetails)
                                    .setOfferToken(offerDetails.offerToken)
                                    .build()
                            )
                        )
                        .build()
                )

                check(billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    "Unable to launch the billing flow. Reason: ${billingResult.debugMessage}"
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error purchase plan", e)

                withContext(Dispatchers.Main) {
                    Toast.makeText(application, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val ProSubscriptionDuration.planId: String
        get() = when (this) {
            ProSubscriptionDuration.ONE_MONTH -> "session-pro-1-month"
            ProSubscriptionDuration.THREE_MONTHS -> "session-pro-3-months"
            ProSubscriptionDuration.TWELVE_MONTHS -> "session-pro-12-months"
        }

    override fun onPostAppStarted() {
        super.onPostAppStarted()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "onBillingServiceDisconnected")
            }

            override fun onBillingSetupFinished(result: BillingResult) {
                Log.d(TAG, "onBillingSetupFinished with $result")
            }
        })
    }

    companion object {
        private const val TAG = "PlayStoreSubscriptionManager"
    }
}