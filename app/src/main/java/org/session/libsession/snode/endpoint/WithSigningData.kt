package org.session.libsession.snode.endpoint

interface WithSigningData {
    fun getSigningData(timestampMs: Long): ByteArray
}