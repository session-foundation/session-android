package org.thoughtcrime.securesms.database.model

import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.protocol.ProFeatures
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

/**
 * Represents local database data for a recipient.
 */
data class RecipientSettings(
    val name: String? = null,
    val muteUntil: Instant? = null,
    val notifyType: NotifyType = NotifyType.ALL,
    val autoDownloadAttachments: Boolean = false,
    val profilePic: UserPic? = null,
    val blocksCommunityMessagesRequests: Boolean = true,
    val profileUpdated: Instant? = null,
    val proData: ProData? = null,
) {
    @Serializable
    data class ProData(
        @Serializable(with = InstantAsMillisSerializer::class)
        val expiry: Instant,
        val genIndexHash: String,
        val features: ProFeatures
    ) {
        fun isExpired(now: Instant): Boolean {
            return expiry <= now
        }
    }
}
