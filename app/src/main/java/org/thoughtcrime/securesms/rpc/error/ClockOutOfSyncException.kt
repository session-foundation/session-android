package org.thoughtcrime.securesms.rpc.error

import org.session.libsignal.utilities.Snode

class ClockOutOfSyncException(
    val origin: Origin,
    cause: Throwable? = null,
) : RuntimeException("Server reported that the client clock is out of sync", cause) {
    sealed interface Origin {
        class SnodeOrigin(val snode: Snode) : Origin
        class Other(val description: String) : Origin
    }
}