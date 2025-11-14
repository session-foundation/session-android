package org.session.libsession.utilities.recipients

import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProFeatures
import org.thoughtcrime.securesms.database.model.RecipientSettings
import java.time.Instant

data class RecipientProStatus(val features: ProFeatures) {
    companion object {
        fun RecipientSettings.ProData.toProStatus(now: Instant): RecipientProStatus? {
            return if (isExpired(now)) {
                null
            } else {
                RecipientProStatus(features)
            }
        }
    }
}

val RecipientProStatus?.isPro: Boolean
    get() = this != null

val RecipientProStatus?.shouldShowProBadge: Boolean
    get() = this?.features?.contains(ProFeature.PRO_BADGE) == true
