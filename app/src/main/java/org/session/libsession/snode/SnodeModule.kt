package org.session.libsession.snode

import android.app.Application
import dagger.Lazy
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Broadcaster
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnodeModule @Inject constructor(
    application: Application,
    val storage: LokiAPIDatabaseProtocol,
    prefs: TextSecurePreferences,
) {

    val broadcaster: Broadcaster = org.thoughtcrime.securesms.util.Broadcaster(application)
    val environment: Environment = prefs.getEnvironment()

    companion object {
        lateinit var sharedLazy: Lazy<SnodeModule>

        @Deprecated("Use properly DI components instead")
        val shared: SnodeModule get() = sharedLazy.get()
    }
}