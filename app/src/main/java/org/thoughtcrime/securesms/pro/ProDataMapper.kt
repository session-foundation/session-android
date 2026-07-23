package org.thoughtcrime.securesms.pro

import android.content.Context
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import java.time.Duration
import java.time.Instant

/**
 * Account-level Pro status slugs (get-pro-status `user_status`, spec §5.2). Closed set on the backend:
 * `never`/`active`/`expired` — note there is NO account-level `revoked` (that's a per-item payment
 * status / an error_code). Opaque on the wire, so an unrecognized value is treated as "not subscribed".
 */
object ProUserStatus {
    const val NEVER = "never"
    const val ACTIVE = "active"
    const val EXPIRED = "expired"
}

/**
 * Map a libsession-parsed get-pro-status response to the app's [ProStatus] domain model. Needs a [Context]
 * to resolve the (client-owned) provider display strings.
 */
fun GetProStatusResponse.toProStatus(nowMs: Long, context: Context): ProStatus {
    return when (userStatus) {
        ProUserStatus.ACTIVE -> {
            val paymentItem = latestPayment ?: return ProStatus.NeverSubscribed
            // Access expiry (incl. grace); "renew due" is expiry minus the grace period.
            val expiryMs = (expiry ?: return ProStatus.NeverSubscribed).toEpochMilli()
            val renewingAtMs = expiryMs - gracePeriod.toMillis()
            val renewingAt = Instant.ofEpochMilli(renewingAtMs)
            val providerData = providerMetadata(paymentItem.paymentProvider, context)
            val duration = paymentItem.plan.toSubscriptionDuration()
            val refundInProgress = refundRequested != null

            if (autoRenewing) {
                ProStatus.Active.AutoRenewing(
                    renewingAt = renewingAt,
                    duration = duration,
                    providerData = providerData,
                    quickRefundExpiry = paymentItem.platformRefundExpiry,
                    refundInProgress = refundInProgress,
                    inGracePeriod = nowMs >= renewingAtMs && nowMs < expiryMs,
                )
            } else {
                ProStatus.Active.Expiring(
                    renewingAt = renewingAt, // equals expiry when the grace period is zero
                    duration = duration,
                    providerData = providerData,
                    quickRefundExpiry = paymentItem.platformRefundExpiry,
                    refundInProgress = refundInProgress,
                )
            }
        }

        ProUserStatus.EXPIRED -> ProStatus.Expired(
            expiredAt = expiry ?: Instant.EPOCH,
            providerData = providerMetadata(
                latestPayment?.paymentProvider ?: PAYMENT_PROVIDER_GOOGLE_PLAY,
                context,
            ),
        )

        // "never" + any unrecognized/future slug -> treat as not subscribed.
        else -> ProStatus.NeverSubscribed
    }
}

/**
 * Map an opaque billing-period slug (spec: `N` + unit `d`/`w`/`m`/`y`; canonical `"1m"`/`"3m"`/`"1y"`)
 * to the app's subscription duration. Unknown/future codes fall back to the one-month presentation.
 */
fun String.toSubscriptionDuration(): ProSubscriptionDuration = when (this) {
    "1y" -> ProSubscriptionDuration.TWELVE_MONTHS
    "3m" -> ProSubscriptionDuration.THREE_MONTHS
    else -> ProSubscriptionDuration.ONE_MONTH // "1m" + fallback
}

fun PaymentProviderMetadata.isFromAnotherPlatform(): Boolean {
    return platform.trim().lowercase() != "google"
}

/**
 * Some UI cases require a special display name for the platform.
 */
fun PaymentProviderMetadata.getPlatformDisplayName(): String {
    return when (platform.trim().lowercase()) {
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
    renewingAt = Instant.now() + Duration.ofDays(14),
    duration = ProSubscriptionDuration.THREE_MONTHS,
    providerData = previewAppleMetaData,
    quickRefundExpiry = Instant.now() + Duration.ofDays(14),
    refundInProgress = false,
    inGracePeriod = false
)

val previewExpiredApple = ProStatus.Expired(
    expiredAt = Instant.now() - Duration.ofDays(14),
    providerData = previewAppleMetaData
)
