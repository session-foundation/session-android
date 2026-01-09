package org.session.libsession.network.model

import org.session.libsignal.utilities.Snode

sealed class OnionDestination(val description: String) {
    class SnodeDestination(val snode: Snode) :
        OnionDestination("Service node ${snode.ip}:${snode.port}")

    class ServerDestination(
        val host: String,
        val target: String,
        val x25519PublicKey: String,
        val scheme: String,
        val port: Int
    ) : OnionDestination(host)
}