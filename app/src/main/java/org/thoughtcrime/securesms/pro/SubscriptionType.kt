package org.thoughtcrime.securesms.pro

import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionDetails
import org.thoughtcrime.securesms.util.State

sealed interface SubscriptionType{
    data object NeverSubscribed: SubscriptionType

    sealed interface Active: SubscriptionType{
        val proStatus: ProStatus.Pro
        val duration: ProSubscriptionDuration
        val nonOriginatingSubscription: SubscriptionDetails? // null if the current subscription is from the current platform

        data class AutoRenewing(
            override val proStatus: ProStatus.Pro,
            override val duration: ProSubscriptionDuration,
            override val nonOriginatingSubscription: SubscriptionDetails?
        ): Active

        data class Expiring(
            override val proStatus: ProStatus.Pro,
            override val duration: ProSubscriptionDuration,
            override val nonOriginatingSubscription: SubscriptionDetails?
        ): Active

    }

    data class Expired(
        val nonOriginatingSubscription: SubscriptionDetails?
    ): SubscriptionType
}

data class SubscriptionState(
    val type: SubscriptionType,
    val refreshState: State<Unit>,
)

fun getDefaultSubscriptionStateData() = SubscriptionState(
    type = SubscriptionType.NeverSubscribed,
    refreshState = State.Loading
)