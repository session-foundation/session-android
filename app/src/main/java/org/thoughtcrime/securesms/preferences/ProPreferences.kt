package org.thoughtcrime.securesms.preferences

import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel

object ProPreferences {
    val FORCE_CURRENT_USER_AS_PRO = PreferenceKey.boolean("pref_force_current_user_pro", false)
    val FORCE_OTHER_USERS_PRO = PreferenceKey.boolean("pref_force_other_users_pro", false)
    val FORCE_INCOMING_MESSAGES_AS_PRO = PreferenceKey.boolean("pref_force_incoming_message_pro", false)
    val FORCE_POST_PRO = PreferenceKey.boolean("pref_force_post_pro", false)
    val HAS_SEEN_PRO_EXPIRING = PreferenceKey.boolean("has_seen_pro_expiring", false)
    val HAS_SEEN_PRO_EXPIRED = PreferenceKey.boolean("has_seen_pro_expired", false)
    val DEBUG_FORCE_NO_BILLING = PreferenceKey.boolean("debug_pro_has_billing", false)
    val DEBUG_IS_WITHIN_QUICK_REFUND = PreferenceKey.boolean("debug_within_quick_refund", false)
    val SUBSCRIPTION_PROVIDER = PreferenceKey.string("session_subscription_provider", null)

    val DEBUG_SUBSCRIPTION_STATUS = PreferenceKey.enum<DebugMenuViewModel.DebugSubscriptionStatus>("debug_subscription_status")
    val DEBUG_PRO_PLAN_STATUS = PreferenceKey.enum<DebugMenuViewModel.DebugProPlanStatus>("debug_pro_plan_status")
}
