package org.thoughtcrime.securesms.pro

import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.preferences.PreferenceKey

object ProPreferenceKeys {
    val FORCE_CURRENT_USER_PRO = PreferenceKey.boolean("pref_force_current_user_pro", false)
    val FORCE_OTHER_USERS_PRO = PreferenceKey.boolean("pref_force_other_users_pro", false)
    val FORCE_INCOMING_MESSAGE_PRO = PreferenceKey.boolean("pref_force_incoming_message_pro", false)
    val FORCE_POST_PRO = PreferenceKey.boolean("pref_force_post_pro", BuildConfig.BUILD_TYPE != "release")

    val DEBUG_PRO_MESSAGE_FEATURES = PreferenceKey.long("debug_pro_message_features", 0L)
    val DEBUG_PRO_PROFILE_FEATURES = PreferenceKey.long("debug_pro_profile_features", 0L)
    val DEBUG_SUBSCRIPTION_STATUS =
        PreferenceKey.enum<DebugMenuViewModel.DebugSubscriptionStatus>("debug_subscription_status")
    val DEBUG_PRO_PLAN_STATUS =
        PreferenceKey.enum<DebugMenuViewModel.DebugProPlanStatus>("debug_pro_plan_status")
    val DEBUG_FORCE_NO_BILLING = PreferenceKey.boolean("debug_pro_has_billing", false)
    val DEBUG_WITHIN_QUICK_REFUND = PreferenceKey.boolean("debug_within_quick_refund", false)
}
