package org.thoughtcrime.securesms.mediasend

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.session.libsignal.utilities.guava.Optional

/**
 * Represents a piece of media that the user has on their device.
 */
@Parcelize
data class Media(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val date: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val bucketId: String?,
    val caption: String?,
) : Parcelable {
    companion object {
        const val ALL_MEDIA_BUCKET_ID: String = "org.thoughtcrime.securesms.ALL_MEDIA"
    }
}
