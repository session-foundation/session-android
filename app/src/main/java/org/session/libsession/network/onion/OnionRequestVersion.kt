package org.session.libsession.network.onion


enum class OnionRequestVersion(val value: String) {
    V3("/loki/v3/lsrpc"),
    V4("/oxen/v4/lsrpc");
}
