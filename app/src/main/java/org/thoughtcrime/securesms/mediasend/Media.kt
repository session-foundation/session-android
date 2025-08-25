package org.thoughtcrime.securesms.mediasend

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a piece of media that the user has on their device.
 */
@Parcelize
data class Media(
    val uri: Uri,
    val filename: String,
    val mimeType: String,
    val date: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val bucketId: String?,
    val caption: String?,
) : Parcelable {

    // The equality check here is performed based only on the URI of the media.
    // This behavior very opinionated and shouldn't really be in a generic equality check in the first place.
    // However there are too much code working under this assumption and we can't simply change it to
    // a generic solution.
    //
    // To later dev: once sufficient refactors are done, we can remove this equality
    // check and rely on the data class default equality check instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Media) return false

        if (uri != other.uri) return false

        return true
    }

    override fun hashCode(): Int {
        return uri.hashCode()
    }


    companion object {
        const val ALL_MEDIA_BUCKET_ID: String = "org.thoughtcrime.securesms.ALL_MEDIA"
    }


}
