package org.thoughtcrime.securesms.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import network.loki.messenger.R
import org.session.libsignal.utilities.Log

object FilenameUtils {
    private const val TAG = "FilenameUtils"

    private fun getFormattedDate(): String {
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss")
        return dateFormatter.format(System.currentTimeMillis())
    }

    @JvmStatic
    fun constructPhotoFilename(): String = "Photo-${getFormattedDate()}.jpg"

    // GIFs picked from Giphy don't have filenames (we just get a GUID) - so synthesize a more reasonable filename such as "GIF-Image-<Date>.gif"
    @JvmStatic
    fun constructPickedGifFilename(context: Context): String = "${context.getString(R.string.gif)}-${context.getString(R.string.image)}-${getFormattedDate()}.gif"

    // As all picked media now has a mandatory filename this method should never get called - but it's here as a last line of defence
    @JvmStatic
    fun constructFallbackMediaFilenameFromMimeType(context: Context, mimeType: String): String {
        return if (MediaUtil.isVideoType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.video)}-${getFormattedDate()}" // Session-Video-<Date>
        } else if (MediaUtil.isGif(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.gif)}-${getFormattedDate()}"   // Session-GIF-<Date>
        } else if (MediaUtil.isImageType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.image)}-${getFormattedDate()}" // Session-Image-<Date>
        } else {
            Log.d(TAG, "Asked to construct a filename for an unsupported media type: $mimeType - returning timestamp.")
            System.currentTimeMillis().toString()
        }
    }

    // Method to attempt to get a filename from a Uri.
    // Note: We typically (now) populate filenames from the file picker Uri - which will work - if
    // we are forced to attempt to obtain the filename from a Uri which does NOT come directly from
    // the file picker then it may or MAY NOT work - or it may work but we get a GUID or an int as
    // the filename rather than the actual filename like "cat.jpg" etc. In such a case returning
    // null from this method means that the calling code must construct a suitable placeholder filename.
    @JvmStatic
    @JvmOverloads // Force creation of two versions of this method - one with and one without the mimeType param
    fun getFilenameFromUri(context: Context, uri: Uri, mimeType: String? = null): String {
        var extractedFilename: String? = null
        val scheme = uri.scheme
        if ("content".equals(scheme, ignoreCase = true)) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            val contentRes = context.contentResolver
            if (contentRes != null) {
                val cursor = contentRes.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        extractedFilename = it.getString(nameIndex)
                    }
                }
            }
        }

        // If the uri did not contain sufficient details to get the filename directly from the content resolver
        // then we'll attempt to extract it from the uri path. For example, it's possible we could end up with
        // a uri path such as:
        //
        //      uri path: /blob/multi-session-disk/image/jpeg/cat.jpeg/3050/3a507d6a-f2f9-41d1-97a0-319de47e3a8d
        //
        // from which we'd want to extract the filename "cat.jpeg".
        if (extractedFilename == null && uri.path != null) {
            extractedFilename = attemptUriPathExtraction(uri.path!!)
        }

        // Uri filename extraction failed - synthesize a filename from the media's MIME type
        if (extractedFilename == null && mimeType != null) {
            constructFallbackMediaFilenameFromMimeType(context, mimeType)
        }

        if (extractedFilename == null) {
            Log.w(TAG, "Failed to get filename from content resolver or Uri path - returning current timestamp as filename due to extreme fallback")
            extractedFilename = System.currentTimeMillis().toString()
        }

        return extractedFilename
    }

    private fun attemptUriPathExtraction(uriPath: String): String? {
        // Split the path by "/" then traverse the segments in reverse order looking for the first one containing a dot
        val segments = uriPath.split("/")
        val extractedFilename = segments.asReversed().firstOrNull { it.contains('.') }

        // If found, return the identified filename, otherwise we'll be returning null
        return extractedFilename
    }
}