package org.thoughtcrime.securesms.rpc.onion

import org.session.libsession.network.onion.Version

class OnionRequest(
    val payload: ByteArray,
    val version: Version = Version.V4
)