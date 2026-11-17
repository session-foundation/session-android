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
    val thumbnailIsVideo: Boolean = false,
) {
    enum class FolderType {
        NORMAL, CAMERA
    }
}