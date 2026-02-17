package org.thoughtcrime.securesms.mediasend

import android.net.Uri


/**
 * Represents a folder that's shown in MediaPickerFolderFragment.
 */
data class MediaFolder(
    val thumbnailUri: Uri?,
    val title: String,
    val itemCount: Int,
    val bucketId: String,
) {
    enum class FolderType {
        NORMAL, CAMERA
    }
}