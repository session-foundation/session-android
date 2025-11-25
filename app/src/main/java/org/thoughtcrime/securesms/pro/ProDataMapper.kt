package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_APP_STORE
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import network.loki.messenger.libsession_util.pro.PaymentProvider
import network.loki.messenger.libsession_util.protocol.PaymentProviderMetadata
import org.thoughtcrime.securesms.pro.api.ServerPlanDuration
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.api.ProDetails.Companion.SERVER_PLAN_DURATION_12_MONTH
import org.thoughtcrime.securesms.pro.api.ProDetails.Companion.SERVER_PLAN_DURATION_3_MONTH
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import java.time.Duration
import java.time.Instant

fun ProDetails.toProStatus(): ProStatus {
    return when (status) {
        ProDetails.DETAILS_STATUS_ACTIVE -> {
            val paymentItem = paymentItems.first()

            if (autoRenewing == true) {
                ProStatus.Active.AutoRenewing(
                    validUntil = expiry!!,
                    duration = paymentItem.planDuration.toSubscriptionDuration(),
                    providerData = paymentItem.paymentProvider.getMetadata(),
                    quickRefundExpiry = paymentItem.platformExpiry
                )
            } else {
                ProStatus.Active.Expiring(
                    validUntil = expiry!!,
                    duration = paymentItem.planDuration.toSubscriptionDuration(),
                    providerData = paymentItem.paymentProvider.getMetadata(),
                    quickRefundExpiry = paymentItem.platformExpiry
                )
            }
        }

        ProDetails.DETAILS_STATUS_EXPIRED -> ProStatus.Expired(
            expiredAt = expiry!!,
            providerData = paymentItems.first().paymentProvider.getMetadata()
        )

        else -> ProStatus.NeverSubscribed
    }
}

fun PaymentProvider.getMetadata(): PaymentProviderMetadata{
    return when(this){
        PAYMENT_PROVIDER_APP_STORE -> BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!
        else -> BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!
    }
}

fun ServerPlanDuration.toSubscriptionDuration(): ProSubscriptionDuration {
    return when(this){
        SERVER_PLAN_DURATION_12_MONTH -> ProSubscriptionDuration.TWELVE_MONTHS
        SERVER_PLAN_DURATION_3_MONTH -> ProSubscriptionDuration.THREE_MONTHS
        else -> ProSubscriptionDuration.ONE_MONTH
    }
}

fun PaymentProviderMetadata.isFromAnotherPlatform(): Boolean {
    return platform.trim().lowercase() != "google"
}

/**
 * Some UI cases require a special display name for the platform.
 */
fun PaymentProviderMetadata.getPlatformDisplayName(): String {
    return when(platform.trim().lowercase()){
        "google" -> store
        else -> platform
    }
}


/**
 * Preview Data - Reusable data for composable previews
 */

val previewAppleMetaData = PaymentProviderMetadata(
    device = "iOS",
    store = "Apple App Store",
    platform = "Apple",
    platformAccount = "Apple Account",
    updateSubscriptionUrl = "https://www.apple.com/account/subscriptions",
    cancelSubscriptionUrl = "https://www.apple.com/account/subscriptions",
    refundPlatformUrl = "https://www.apple.com/account/subscriptions",
    refundSupportUrl = "https://www.apple.com/account/subscriptions",
    refundStatusUrl = "https://www.apple.com/account/subscriptions"
)

val previewAutoRenewingApple = ProStatus.Active.AutoRenewing(
    validUntil = Instant.now() + Duration.ofDays(14),
    duration = ProSubscriptionDuration.THREE_MONTHS,
    providerData = previewAppleMetaData,
    quickRefundExpiry = Instant.now() + Duration.ofDays(14)
)

val previewExpiredApple = ProStatus.Expired(
    expiredAt = Instant.now() - Duration.ofDays(14),
    providerData = previewAppleMetaData
)


