package org.session.libsession.utilities.recipients

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import org.thoughtcrime.securesms.database.model.NotifyType
import java.time.Instant

/**
 * Returns the effective notify type for the recipient, taking account of the time the recipient
 * should be muted for.
 *
 * @param now The current time, defaults to the current system time.
 */
fun Recipient.effectiveNotifyType(now: Instant = Instant.now()): NotifyType {
    if (mutedUntil != null && now.isBefore(mutedUntil)) {
        return NotifyType.NONE
    }

    return notifyType
}


/**
 * Transforms a flow of [Recipient]s to emit the same recipient whenever there's change to
 * [effectiveNotifyType] - a notify type that takes into account the muted duration of the recipient.
 */
fun Flow<Recipient>.repeatedWithEffectiveNotifyTypeChange(): Flow<Recipient> {
    return transform { r ->
        val now = Instant.now()
        if (r.mutedUntil != null && now.isBefore(r.mutedUntil)) {
            emit(r)
            val expirationTime = r.mutedUntil.toEpochMilli() - now.toEpochMilli()
            delay(expirationTime)
            emit(r)
        } else {
            emit(r)
        }
    }
}