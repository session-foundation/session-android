package org.thoughtcrime.securesms.pro

import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.util.State
import java.time.Instant

sealed interface ProStatus{
    data object NeverSubscribed: ProStatus

    sealed interface Active: ProStatus{
        val validUntil: Instant
        val duration: ProSubscriptionDuration
        val subscriptionDetails: SubscriptionDetails

        data class AutoRenewing(
            override val validUntil: Instant,
            override val duration: ProSubscriptionDuration,
            override val subscriptionDetails: SubscriptionDetails
        ): Active

        data class Expiring(
            override val validUntil: Instant,
            override val duration: ProSubscriptionDuration,
            override val subscriptionDetails: SubscriptionDetails
        ): Active

    }

    data class Expired(
        val expiredAt: Instant,
        val subscriptionDetails: SubscriptionDetails
    ): ProStatus
}

data class SubscriptionState(
    val type: ProStatus,
    val showProBadge: Boolean,
    val refreshState: State<Unit>,
)

data class SubscriptionDetails(
    val device: String,
    val store: String,
    val platform: String,
    val platformAccount: String,
    val subscriptionUrl: String,
    val refundUrl: String,
){
    fun isFromAnotherPlatform(): Boolean {
        return platform.trim().lowercase() != "google"
    }

    /**
     * Some UI cases require a special display name for the platform.
     */
    fun getPlatformDisplayName(): String {
        return when(platform.trim().lowercase()){
            "google" -> store
            else -> platform
        }
    }
}

fun getDefaultSubscriptionStateData() = SubscriptionState(
    type = ProStatus.NeverSubscribed,
    refreshState = State.Loading,
    showProBadge = false
)