package org.thoughtcrime.securesms.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Locale
import network.loki.messenger.R
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsignal.utilities.Log


object FilenameUtils {
    private const val TAG = "FilenameUtils"

    private fun getFormattedDate(timestamp: Long? = null): String {
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault())
        return dateFormatter.format( timestamp ?: System.currentTimeMillis() )
    }

    @JvmStatic
    fun constructPhotoFilename(context: Context): String = "${context.getString(R.string.app_name)}-Photo-${getFormattedDate()}.jpg"

    @JvmStatic
    fun constructNewVoiceMessageFilename(context: Context): String = context.getString(R.string.app_name) + "-" + context.getString(R.string.messageVoice).replace(" ", "") + "_${getFormattedDate()}" + ".aac"

    // Method to synthesize a suitable filename for a legacy voice message that has no filename whatsoever
    @JvmStatic
    fun constructVoiceMessageFilenameFromAttachment(context: Context, attachment: Attachment): String {
        var constructedFilename = ""
        val appNameString = context.getString(R.string.app_name)
        val voiceMessageString = context.getString(R.string.messageVoice).replace(" ", "")

        // We SHOULD always have a uri path - but it's not guaranteed
        val uriPath = attachment.dataUri?.path
        if (uriPath != null) {
            // The Uri path contains a timestamp for when the attachment was written, typically in the form "/part/1736914338425/4",
            // where the middle element ("1736914338425" in this case) equates to: Wednesday, 15 January 2025 15:12:18.425 (in the GST+11 timezone).
            // The final "/4" is likely the part number.
            attachment.dataUri!!.pathSegments.let { segments ->
                // If we can extract a timestamp from the Uri path then we'll use that in our voice message filename synthesis
                if (segments.size >= 2 && segments[1].toLongOrNull() != null) {
                    val extractedTimestamp = segments[1].toLong()
                    constructedFilename = appNameString + "-" + voiceMessageString + "_${getFormattedDate(extractedTimestamp)}" + ".aac"
                }
            }
        }

        return if (constructedFilename.isEmpty()) {
            // If we didn't have a Uri path or couldn't extract the timestamp then we'll call the voice message "Session-VoiceMessage.aac"..
            // Note: On save, should a file with this name already exist it will have an incremental number appended, e.g.,
            // Session-VoiceMessage-1.aac, Session-VoiceMessage-2.aac etc.
            "$appNameString-$voiceMessageString.aac"
        } else {
            // ..otherwise we'll return a more accurate filename such as "Session-VoiceMessage_2025-01-15-151218.aac".
            constructedFilename
        }
    }

    // As all picked media now has a mandatory filename this method should never get called - but it's here as a last line of defence
    @JvmStatic
    fun constructFallbackMediaFilenameFromMimeType(
        context: Context,
        mimeType: String?,
        fileTimestamp: Long?
    ): String {
        val timestamp = "_${getFormattedDate(fileTimestamp)}"

        return if (MediaUtil.isVideoType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.video)}$timestamp" // Session-Video_<Date>
        } else if (MediaUtil.isGif(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.gif)}$timestamp"   // Session-GIF_<Date>
        } else if (MediaUtil.isImageType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.image)}$timestamp" // Session-Image_<Date>
        } else {
            Log.d(TAG, "Asked to construct a filename for an unsupported media type: $mimeType.")
            "${context.getString(R.string.app_name)}$timestamp" // Session_<Date> - best we can do
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
    fun getFilenameFromUri(context: Context, uri: Uri?, mimeType: String? = null): String {
        var extractedFilename: String? = null

        if (uri != null) {
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
            if (extractedFilename.isNullOrEmpty() && uri.path != null) {
                extractedFilename = attemptUriPathExtraction(uri.path!!)
            }
        }

        // Uri filename extraction failed - synthesize a filename from the media's MIME type.
        // Note: Giphy picked GIFs will use this to get a filename like "Session-GIF-<Date>" - but pre-saved GIFs
        // chosen via the file-picker or similar will use the existing saved filename as they will be caught in
        // the filename extraction code above.
        if (extractedFilename.isNullOrEmpty()) {
            extractedFilename = constructFallbackMediaFilenameFromMimeType(context, mimeType, getTimestampFromUri(uri?.path))
        }

        return extractedFilename!!
    }

    private fun attemptUriPathExtraction(uriPath: String): String? {
        // Split the path by "/" then traverse the segments in reverse order looking for the first one containing a dot
        val segments = uriPath.split("/")
        val extractedFilename = segments.asReversed().firstOrNull { it.contains('.') }

        // If found, return the identified filename, otherwise we'll be returning null
        return extractedFilename
    }

    private fun getTimestampFromUri(uriPath: String?): Long?{
        val segments = uriPath?.split("/")
        // we assume that at this stage we have a format that looks like /part/1111111111/123 with 1111111111 being the creation date timestamp
        return try {
            segments?.getOrNull(2)?.toLong()
        } catch (e: Exception){
            null
        }
    }
}