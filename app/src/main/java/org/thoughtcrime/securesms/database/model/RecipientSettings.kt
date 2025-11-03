package org.thoughtcrime.securesms.database.model

import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.utilities.recipients.ProStatus
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
    val proStatus: ProStatus = ProStatus.None,
    val profileUpdated: Instant? = null,
)
