package org.session.libsession.messaging.file_server

import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.Curve25519
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.utilities.serializable.HttpUrlSerializer
import org.session.libsignal.utilities.Hex

@Serializable
data class FileServer(
    @Serializable(with = HttpUrlSerializer::class)
    val url: HttpUrl,
    val ed25519PublicKeyHex: String
) {
    constructor(url: String, ed25519PublicKeyHex: String) : this(url.toHttpUrl(), ed25519PublicKeyHex)

    val x25519PubKeyHex: String by lazy {
        Hex.toStringCondensed(
            Curve25519.pubKeyFromED25519(Hex.fromStringCondensed(ed25519PublicKeyHex))
        )
    }
}

val HttpUrl.isOfficial: Boolean
    get() = host.endsWith(".getsession.org", ignoreCase = true)
