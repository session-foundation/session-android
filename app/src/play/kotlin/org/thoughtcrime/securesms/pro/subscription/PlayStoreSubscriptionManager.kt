package org.thoughtcrime.securesms.pro.subscription

import android.app.Application
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetailsResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Google Play Store implementation of our subscription manager
 */
@Singleton
class PlayStoreSubscriptionManager @Inject constructor(
    private val application: Application,
    private val currentActivityObserver: CurrentActivityObserver,
    private val prefs: TextSecurePreferences,
    private val loginStateRepository: LoginStateRepository,
    proStatusManager: ProStatusManager,
    @param:ManagerScope scope: CoroutineScope,
) : SubscriptionManager(proStatusManager, scope) {
    override val id = "google_play_store"
    override val name = "Google Play Store"
    override val description = ""
    override val iconRes = null

    // specifically test the google play billing
    private val _playBillingAvailable = MutableStateFlow(false)

    // generic billing support method. Uses the property above and also checks the debug pref
    override val supportsBilling: StateFlow<Boolean> = combine(
        _playBillingAvailable,
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_FORCE_NO_BILLING } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.getDebugForceNoBilling() },
        ){ available, forceNoBilling ->
            !forceNoBilling && available
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val billingClient by lazy {
        BillingClient.newBuilder(application)
            .setListener { result, purchases ->
                Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label, "Billing callback. Result: $result, Purchases: ${purchases?.map { it.orderId }}")

                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    purchases.firstOrNull()?.let{
                        val expected = sha256Hex(loginStateRepository.requireLocalNumber())
                        val purchaseAccountId = it.accountIdentifiers?.obfuscatedAccountId

                        if (purchaseAccountId != expected) {
                            Log.w(TAG, "Ignoring purchase: Belongs to different accountID (with this same playstore account)")
                            return@setListener
                        }

                        Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label,
                            "Billing callback. We have a purchase [${it.orderId}]. Acknowledged? ${it.isAcknowledged}")

                        onPurchaseSuccessful(
                            orderId = it.orderId ?: "",
                            paymentId = it.purchaseToken
                        )
                    }
                } else {
                    Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "Purchase failed or cancelled: $result")
                    scope.launch {
                        _purchaseEvents.emit(PurchaseEvent.Cancelled)
                    }
                }
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

    override suspend fun purchasePlan(subscriptionDuration: ProSubscriptionDuration): Result<Unit> {
        try {
            val activity = checkNotNull(currentActivityObserver.currentActivity.value) {
                "No current activity available to launch the billing flow"
            }

            val result = getProductDetails()

            check(result?.billingResult?.responseCode == BillingClient.BillingResponseCode.OK) {
                "Failed to query product details. Reason: ${result?.billingResult}"
            }

            val productDetails = checkNotNull(result.productDetailsList?.firstOrNull()) {
                "Unable to get the product: product for given id is null"
            }

            val planId = subscriptionDuration.id

            val offerDetails = checkNotNull(productDetails.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == planId }) {
                    "Unable to find a plan with id $planId"
                }

            // Check for existing subscription
            val existingPurchase = getExistingSubscription()
            val obfuscatedId = sha256Hex(loginStateRepository.requireLocalNumber())

            val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
                .setObfuscatedAccountId(obfuscatedId)
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerDetails.offerToken)
                            .build()
                    )
                )

            // If user has an existing subscription, configure upgrade/downgrade
            if (existingPurchase != null) {
                Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label, "Found existing subscription, configuring upgrade/downgrade with WITHOUT_PRORATION")

                billingFlowParamsBuilder.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(existingPurchase.purchaseToken)
                        // WITHOUT_PRORATION ensures new plan only bills when existing plan expires/renews
                        // This applies whether the subscription is auto-renewing or canceled
                        .setSubscriptionReplacementMode(
                            BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION
                        )
                        .build()
                )
            }

            val billingResult = billingClient.launchBillingFlow(
                activity,
                billingFlowParamsBuilder.build()
            )

            check(billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                "Unable to launch the billing flow. Reason: ${billingResult.debugMessage}"
            }

            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(DebugLogGroup.PRO_SUBSCRIPTION.label, "Error purchase plan", e)

            // pass the purchase error information to subscribers
            _purchaseEvents.emit(PurchaseEvent.Failed.GenericError())

            return Result.failure(e)
        }
    }

    private fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return Hex.toStringCondensed(digest)
    }

    private suspend fun getProductDetails(): ProductDetailsResult? {
        if(!billingClient.isReady || !_playBillingAvailable.value) return null

        return billingClient.queryProductDetails(
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
    }

    override fun onPostAppStarted() {
        super.onPostAppStarted()

        if (!hasPlayServices() || !hasPlayStore()) {
            _playBillingAvailable.update { false }
            Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "Play Billing unavailable (GMS/Play Store missing).")
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {

                _playBillingAvailable.update { false }
            }

            override fun onBillingSetupFinished(result: BillingResult) {
                Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label, "onBillingSetupFinished with $result")
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _playBillingAvailable.update { true }
                } else {
                    _playBillingAvailable.update { false }
                    runCatching { billingClient.endConnection() }
                }
            }
        })
    }

    private fun hasPlayServices(): Boolean {
        val gms = GoogleApiAvailability.getInstance()
        return gms.isGooglePlayServicesAvailable(application) == ConnectionResult.SUCCESS
    }

    private fun hasPlayStore(): Boolean {
        return try {
            val ai = application.packageManager.getApplicationInfo("com.android.vending", 0)
            ai.enabled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gets the user's existing active subscription if one exists.
     * Returns null if no active subscription is found.
     */
    private suspend fun getExistingSubscription(): Purchase? {
        if(!billingClient.isReady || !_playBillingAvailable.value) return null

        return try {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()

            val result = billingClient.queryPurchasesAsync(params)

            // Return the first active subscription
            result.purchasesList.firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
        } catch (e: Exception) {
            Log.e(DebugLogGroup.PRO_SUBSCRIPTION.label, "Error querying existing subscription", e)
            null
        }
    }

    override suspend fun hasValidSubscription(): Boolean {
        // if in debug mode, always return true
        return if(prefs.forceCurrentUserAsPro()) true
        else getExistingSubscription() != null
    }

    @Throws(Exception::class)
    override suspend fun getSubscriptionPrices(): List<SubscriptionManager.SubscriptionPricing> {
        val result = getProductDetails()
        check(result?.billingResult?.responseCode == BillingClient.BillingResponseCode.OK) {
            "Failed to query product details. Reason: ${result?.billingResult}"
        }

        val productDetails = result.productDetailsList?.firstOrNull()
            ?: run {
                Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "No ProductDetails returned for product id session_pro")
                return emptyList()
            }

        val offersByBasePlan = productDetails.subscriptionOfferDetails
            ?.associateBy { it.basePlanId }
            .orEmpty()

        // For each duration we support, find the matching offer by basePlanId
        return availablePlans.mapNotNull { duration ->
            val offer = offersByBasePlan[duration.id]
            if (offer == null) {
                Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "No offer found for basePlanId=${duration.id}")
                return@mapNotNull null
            }

            val phases = offer.pricingPhases.pricingPhaseList

            val pricing = phases.firstOrNull {
                it.recurrenceMode == com.android.billingclient.api.ProductDetails.RecurrenceMode.INFINITE_RECURRING
            } ?:return@mapNotNull null  // skip if not found

            SubscriptionManager.SubscriptionPricing(
                subscriptionDuration = duration,
                priceAmountMicros = pricing.priceAmountMicros,
                priceCurrencyCode = pricing.priceCurrencyCode,
                billingPeriodIso = pricing.billingPeriod,  // e.g., P1M, P3M, P1Y
                formattedTotal = pricing.formattedPrice    // Play-formatted localized total
            )
        }
    }

    companion object {
        private const val TAG = "PlayStoreSubscriptionManager"
    }
}