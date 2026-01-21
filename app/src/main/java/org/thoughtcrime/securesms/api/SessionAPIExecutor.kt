package org.thoughtcrime.securesms.api

import okhttp3.HttpUrl
import org.session.libsignal.utilities.ByteArraySlice

typealias SessionApiRequest = ByteArray

class SessionApiResponse(
    val code: Int,
    val body: SessionAPIResponseBody,
)

sealed interface SessionAPIResponseBody {
    class Text(val text: String) : SessionAPIResponseBody
    class Bytes(val bytes: ByteArraySlice) : SessionAPIResponseBody
}

sealed interface SessionDestination {
    data class Snode(val snode: org.session.libsignal.utilities.Snode) : SessionDestination
    data class HttpServer(val url: HttpUrl, val x25519PubKeyHex: String) : SessionDestination
}

typealias SessionApiExecutor = ApiExecutor<SessionDestination, SessionApiRequest, SessionApiResponse>