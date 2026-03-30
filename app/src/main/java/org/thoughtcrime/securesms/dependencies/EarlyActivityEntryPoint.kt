package org.thoughtcrime.securesms.dependencies

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import org.session.libsession.utilities.TextSecurePreferences

/**
 * An entrypoint for activities that need very early access to dependencies. This is only necessary
 * for activities that want dependencies before `super.onCreate()` is called.
 */
@EntryPoint
@InstallIn(ActivityComponent::class)
interface EarlyActivityEntryPoint {
    val prefs: TextSecurePreferences
}