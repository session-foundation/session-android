package org.thoughtcrime.securesms.pro

import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.util.State

sealed interface SubscriptionType{
    data object NeverSubscribed: SubscriptionType

    sealed interface Active: SubscriptionType{
        val proStatus: ProStatus.Pro
        val duration: ProSubscriptionDuration
        val subscriptionDetails: SubscriptionDetails

        data class AutoRenewing(
            override val proStatus: ProStatus.Pro,
            override val duration: ProSubscriptionDuration,
            override val subscriptionDetails: SubscriptionDetails
        ): Active

        data class Expiring(
            override val proStatus: ProStatus.Pro,
            override val duration: ProSubscriptionDuration,
            override val subscriptionDetails: SubscriptionDetails
        ): Active

    }

    data class Expired(
        val subscriptionDetails: SubscriptionDetails
    ): SubscriptionType
}

data class SubscriptionState(
    val type: SubscriptionType,
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
    type = SubscriptionType.NeverSubscribed,
    refreshState = State.Loading
)