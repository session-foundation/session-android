package org.thoughtcrime.securesms.pro

import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import java.time.Instant

sealed interface SubscriptionState{
    data object NeverSubscribed: SubscriptionState

    sealed interface Active: SubscriptionState{
        val proStatus: ProStatus.Pro
        val type: ProSubscriptionDuration

        //todo PRO we need a way to know which store the subscription is from

        data class AutoRenewing(
            override val proStatus: ProStatus.Pro,
            override val type: ProSubscriptionDuration
        ): Active

        data class Expiring(
            override val proStatus: ProStatus.Pro,
            override val type: ProSubscriptionDuration
        ): Active
    }

    data object Expired: SubscriptionState
}