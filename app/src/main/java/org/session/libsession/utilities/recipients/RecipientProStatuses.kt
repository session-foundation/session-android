package org.session.libsession.utilities.recipients

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * Transform the flow into emitting the same recipient whenever the pro status is about to change
 * according to its validity period.
 */
fun Flow<Recipient>.repeatedWithEffectiveProStatusChange(): Flow<Recipient> {
    return transform { r ->
        emit(r)
        if (r.proStatus is ProStatus.Pro) {
            val validUntil = (r.proStatus as ProStatus.Pro).validUntil
            if (validUntil != null) {
                val expirationTime = validUntil.toEpochMilli() - System.currentTimeMillis()
                delay(expirationTime)
            }
        }
        emit(r)
    }
}
