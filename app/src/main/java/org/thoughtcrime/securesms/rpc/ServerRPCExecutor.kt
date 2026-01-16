package org.thoughtcrime.securesms.rpc

import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest
import okhttp3.Response as HttpResponse

data class ServerAddress(
    val url: HttpUrl,
    val x25519PubKeyHex: String,
)

typealias ServerRPCExecutor = RPCExecutor<ServerAddress, HttpRequest, HttpResponse>
