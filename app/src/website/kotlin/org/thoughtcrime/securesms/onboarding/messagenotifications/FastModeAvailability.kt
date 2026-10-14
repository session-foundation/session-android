package org.thoughtcrime.securesms.onboarding.messagenotifications

import android.app.Application

/**
 * Fast mode can never work on this flavour, however capable the device is: it binds
 * `NoOpTokenFetcher`, whose token is permanently null, and `PushRegistrationHandler` combines on
 * `token.filterNotNull()` — so registration is never even attempted. Reporting it unavailable also
 * preserves the battery-optimisation prompt in `HomeViewModel`, which is gated on the user not being
 * in fast mode and is the only mitigation left once delivery depends on `BackgroundPollWorker`.
 */
internal fun Application.isFastModeAvailable(): Boolean = false
