package org.thoughtcrime.securesms.pro.subscription

import javax.inject.Inject

/**
 * The Google Play Store implementation of our subscription manager
 */
class PlayStoreSubscriptionManager @Inject constructor(): SubscriptionManager {
    override val id = "google_play_store"
    override val displayName = ""
    override val description = ""
    override val iconRes = null

    override fun purchasePlan(subscriptionDuration: ProSubscriptionDuration) {
        //todo PRO implement
    }
}