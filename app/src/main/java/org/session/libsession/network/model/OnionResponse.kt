package org.session.libsession.network.model

import org.session.libsignal.utilities.ByteArraySlice

data class OnionResponse(
    val info: Map<*, *>,
    val body: ByteArraySlice? = null
) {
    val code: Int? get() = info["code"] as? Int
    val message: String? get() = info["message"] as? String
}