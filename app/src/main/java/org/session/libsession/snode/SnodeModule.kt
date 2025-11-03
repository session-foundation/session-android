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
    val storage: LokiAPIDatabaseProtocol,
    prefs: TextSecurePreferences,
) {
    val environment: Environment = prefs.getEnvironment()

    companion object {
        lateinit var sharedLazy: Lazy<SnodeModule>

        @Deprecated("Use properly DI components instead")
        val shared: SnodeModule get() = sharedLazy.get()
    }
}