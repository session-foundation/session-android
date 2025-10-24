package org.session.libsession.messaging.file_server

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.utilities.serializable.HttpSerializer

@Serializable
data class FileServer(
    @Serializable(with = HttpSerializer::class)
    val url: HttpUrl,
    val publicKeyHex: String
) {
    constructor(url: String, publicKeyHex: String) : this(url.toHttpUrl(), publicKeyHex)
}

val HttpUrl.isOfficial: Boolean
    get() = host.endsWith(".getsession.org", ignoreCase = true)
