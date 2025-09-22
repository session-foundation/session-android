package org.thoughtcrime.securesms

import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.Slide

data class MediaPreviewArgs(
    val slide: Slide,
    val mmsRecord: MmsMessageRecord,
    val conversationAddress: Address,
)
