package org.thoughtcrime.securesms.database.model

import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.util.BitSet
import network.loki.messenger.libsession_util.util.Conversation
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
        val showProBadge: Boolean,
    ) {

        constructor(
            info: Conversation.ProProofInfo,
            features: BitSet<ProProfileFeature>,
        ): this(
            expiry = info.expiry,
            genIndexHash = info.genIndexHash.data.toHexString(),
            showProBadge = features.contains(ProProfileFeature.PRO_BADGE),
        )
        fun isExpired(now: Instant): Boolean {
            return expiry <= now
        }
    }
}
