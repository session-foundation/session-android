package org.session.libsession.messaging.sending_receiving.attachments;

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable


@Parcelize
@Serializable
data class AttachmentId(
    val rowId: Long,
    val uniqueId: Long
) : Parcelable {
    fun isValid(): Boolean {
        return rowId >= 0 && uniqueId >= 0
    }

    fun toStrings(): Array<String> {
        return arrayOf(rowId.toString(), uniqueId.toString())
    }

}
