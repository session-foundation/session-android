package org.thoughtcrime.securesms.preferences.prosettings

import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.preferences.PreferenceKey

object ProSettingsPreferenceKeys {
    val FORCE_CURRENT_USER_PRO = PreferenceKey.boolean("pref_force_current_user_pro", false)
    val DEBUG_WITHIN_QUICK_REFUND = PreferenceKey.boolean("debug_within_quick_refund", false)
    val DEBUG_PRO_PLAN_STATUS = PreferenceKey.enum<DebugMenuViewModel.DebugProPlanStatus>("debug_pro_plan_status")
}
