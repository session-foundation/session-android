package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.Curve25519
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ProBackendConfig(
    val url: HttpUrl,
    val ed25519PubKeyHex: String,
) {
    val ed25519PubKey: ByteArray = ed25519PubKeyHex.hexToByteArray()
    val x25519PubKey: ByteArray = Curve25519.pubKeyFromED25519(ed25519PubKey)
    val x25519PubKeyHex: String = x25519PubKey.toHexString()

    constructor(
        url: String,
        ed25519PubKeyHex: String,
    ) : this(
        url = url.toHttpUrl(),
        ed25519PubKeyHex = ed25519PubKeyHex,
    )
}
