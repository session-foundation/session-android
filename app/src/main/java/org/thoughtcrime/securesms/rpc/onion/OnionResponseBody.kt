package org.thoughtcrime.securesms.rpc.onion

import org.session.libsignal.utilities.ByteArraySlice

sealed interface OnionResponseBody {
    class Text(val text: String) : OnionResponseBody
    class Bytes(val bytes: ByteArraySlice) : OnionResponseBody
}