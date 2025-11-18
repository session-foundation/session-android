package org.thoughtcrime.securesms.pro

import org.thoughtcrime.securesms.pro.api.ServerPlanDuration
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.api.ProDetails.Companion.SERVER_PLAN_DURATION_12_MONTH
import org.thoughtcrime.securesms.pro.api.ProDetails.Companion.SERVER_PLAN_DURATION_3_MONTH
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration

fun ProDetails.toProStatus(): ProStatus {
    return when (status) {
        ProDetails.DETAILS_STATUS_ACTIVE -> {
            if (autoRenewing == true) {
                ProStatus.Active.AutoRenewing(
                    validUntil = expiry!!,
                    duration = paymentItems.first().planDuration.toSubscriptionDuration(),
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "",
                        refundUrl = "",
                    )
                )
            } else {
                ProStatus.Active.Expiring(
                    validUntil = expiry!!,
                    duration = paymentItems.first().planDuration.toSubscriptionDuration(),
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "",
                        refundUrl = "",
                    )
                )
            }
        }

        ProDetails.DETAILS_STATUS_EXPIRED -> ProStatus.Expired(
            expiredAt = expiry!!,
            subscriptionDetails = SubscriptionDetails(
                device = "Android",
                store = "Google Play Store",
                platform = "Google",
                platformAccount = "Google account",
                subscriptionUrl = "",
                refundUrl = "",
            )
        )

        else -> ProStatus.NeverSubscribed
    }
}

fun ServerPlanDuration.toSubscriptionDuration(): ProSubscriptionDuration {
    return when(this){
        SERVER_PLAN_DURATION_12_MONTH -> ProSubscriptionDuration.TWELVE_MONTHS
        SERVER_PLAN_DURATION_3_MONTH -> ProSubscriptionDuration.THREE_MONTHS
        else -> ProSubscriptionDuration.ONE_MONTH
    }
}