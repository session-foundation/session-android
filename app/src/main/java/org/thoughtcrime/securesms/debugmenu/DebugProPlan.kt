package org.thoughtcrime.securesms.debugmenu

import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager

data class DebugProPlan(
    val manager: SubscriptionManager,
    val plan: ProSubscriptionDuration
) {
    val label: String get() = "${manager.id}-${plan.name}"
}