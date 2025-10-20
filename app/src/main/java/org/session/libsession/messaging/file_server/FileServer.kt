package org.session.libsession.messaging.file_server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class FileServer(
    val url: HttpUrl,
    val publicKeyHex: String
) {
    constructor(url: String, publicKeyHex: String) : this(url.toHttpUrl(), publicKeyHex)

}

val HttpUrl.isOfficial: Boolean
    get() = host.endsWith(".getsession.org", ignoreCase = true)
