package org.thoughtcrime.securesms.onboarding.messagenotifications

import android.app.Application
import android.content.pm.PackageManager

internal fun Application.isFastModeAvailable(): Boolean {
    return try {
        applicationContext.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
