package org.thoughtcrime.securesms.pro

import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.util.State

sealed interface SubscriptionType{
    data object NeverSubscribed: SubscriptionType

    sealed interface Active: SubscriptionType{
        val proStatus: ProStatus.Pro
        val duration: ProSubscriptionDuration
        val nonOriginatingSubscription: NonOriginatingSubscription? // null if the current subscription is from the current platform

        data class AutoRenewing(
            override val proStatus: ProStatus.Pro,
            override val duration: ProSubscriptionDuration,
            override val nonOriginatingSubscription: NonOriginatingSubscription?
        ): Active

        data class Expiring(
            override val proStatus: ProStatus.Pro,
            override val duration: ProSubscriptionDuration,
            override val nonOriginatingSubscription: NonOriginatingSubscription?
        ): Active

        /**
         * A structure representing a non-originating subscription
         * For example if a user bought Pro on their iOS device through the Apple Store
         * This will help us direct them to their original subscription platform if they want
         * to update or cancel Pro
         */
        data class NonOriginatingSubscription(
            val device: String,
            val store: String,
            val platform: String,
            val platformAccount: String,
            val urlSubscription: String,
        )
    }

    data object Expired: SubscriptionType
}

data class SubscriptionState(
    val type: SubscriptionType,
    val refreshState: State<Unit>,
)

fun getDefaultSubscriptionStateData() = SubscriptionState(
    type = SubscriptionType.NeverSubscribed,
    refreshState = State.Loading
)