package org.session.libsession.snode

import org.session.libsession.utilities.Environment
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Broadcaster

class SnodeModule(
    val storage: LokiAPIDatabaseProtocol, val broadcaster: Broadcaster, val environment: Environment
) {

    companion object {
        @Deprecated("Use properly DI components instead")
        lateinit var shared: SnodeModule

        val isInitialized: Boolean get() = Companion::shared.isInitialized

        fun configure(storage: LokiAPIDatabaseProtocol, broadcaster: Broadcaster, environment: Environment) {
            if (isInitialized) { return }
            shared = SnodeModule(storage, broadcaster, environment)
        }
    }
}