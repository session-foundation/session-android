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

    // Filename for when we take a photo from within Session
    @JvmStatic
    fun constructPhotoFilename(context: Context): String = "${context.getString(R.string.app_name)}-Photo-${getFormattedDate()}.jpg"

    // Filename for when we create a new voice message
    @JvmStatic
    fun constructNewVoiceMessageFilename(context: Context): String = context.getString(R.string.app_name) + "-" + context.getString(R.string.messageVoice).replace(" ", "") + "_${getFormattedDate()}" + ".aac"

    // Method to synthesize a suitable filename for a voice message that we have been sent.
    // Note: If we have a file as an attachment then it has a `isVoiceNote` property which
    @JvmStatic
    fun constructAudioMessageFilenameFromAttachment(context: Context, attachment: Attachment): String {
        val appNameString = context.getString(R.string.app_name)


        // Try and get the file extension, e.g., from "audio/aac" extract the "aac" part etc.
        val fileExtensionSegments = attachment.contentType.split("/")
        val fileExtension = if (fileExtensionSegments.size == 2) fileExtensionSegments[1] else ""

        // We SHOULD always have a uri path - but it's not guaranteed
        val uriPath = attachment.dataUri?.path
        Log.w("ACL", "Extracted uri path: $uriPath")

        val timestamp = if (uriPath.isNullOrEmpty()) System.currentTimeMillis() else getTimestampFromUri(uriPath)
        Log.i("ACL", "Going with timestamp: $timestamp")

        // Return the filename using either the "VoiceMessage" or "Audio" string depending on the attachment type
        val audioTypeString = if (attachment.isVoiceNote) context.getString(R.string.messageVoice).replace(" ", "") else context.getString(R.string.audio)
        return "$appNameString-${audioTypeString}_${getFormattedDate(timestamp)}.$fileExtension"

//        if (!uriPath.isNullOrEmpty()) {
//            // The Uri path contains a timestamp for when the attachment was written, typically in the form "/part/1736914338425/4",
//            // where the middle element ("1736914338425" in this case) equates to: Wednesday, 15 January 2025 15:12:18.425 (in the GST+11 timezone).
//            // The final "/4" is likely the part number.
//
//            // CLOSE TO OG
////            val databaseWriteTimestamp = getTimestampFromUri(uriPath)
////            Log.i("ACL", "Extracted timestamp: $databaseWriteTimestamp")
////
////            if (databaseWriteTimestamp != null) {
////                return appNameString + "-" + audioTypeString + "_${getFormattedDate(databaseWriteTimestamp)}" + ".aac"
////            }
//
//            val timestamp = getTimestampFromUri(uriPath) ?: System.currentTimeMillis()
//            Log.i("ACL", "Extracted timestamp999: $timestamp")
//
//            return "$appNameString-${audioTypeString}_${getFormattedDate(timestamp)}.$fileExtension"
        //}

        // If we didn't have a Uri path or couldn't extract the timestamp then we'll call the voice message "Session-VoiceMessage.aac"..
        // Note: On save, should a file with this name already exist it will have an incremental number appended, e.g.,
        // Session-VoiceMessage-1.aac, Session-VoiceMessage-2.aac etc.
        //Log.w("ACL", "Couldn't find timestamp in uri path: $uriPath")
        //return "$appNameString-$audioTypeString.aac"
    }

    // As all picked media now has a mandatory filename this method should never get called - but it's here as a last line of defence
    @JvmStatic
    fun constructFallbackMediaFilenameFromMimeType(
        context: Context,
        mimeType: String?,
        timestamp: Long?
    ): String {
        // If we couldn't extract a timestamp from a Uri then the best we can do is use now.
        // Note: Once a file is created with this timestamp it is maintained with that timestamp so
        // we do not have issues such as saving the file multiple times resulting in multiple filenames
        // where each file uses the "now" timestamp it was saved at (although multiple files will
        // have -1, -2, -3 etc. suffixes to prevent overwriting any file.
        val guaranteedTimestamp = timestamp ?: System.currentTimeMillis()
        val formattedDate = "_${getFormattedDate(guaranteedTimestamp)}"
        val fileExtension = mimeType?.split("/")?.get(1) ?: ""

        return if (MediaUtil.isVideoType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.video)}$formattedDate.$fileExtension" // Session-Video_<Date>
        } else if (MediaUtil.isGif(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.gif)}$formattedDate.$fileExtension"   // Session-GIF_<Date>
        } else if (MediaUtil.isImageType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.image)}$formattedDate.$fileExtension" // Session-Image_<Date>
        } else if (MediaUtil.isAudioType(mimeType)) {
            "${context.getString(R.string.app_name)}-${context.getString(R.string.audio)}$formattedDate.$fileExtension" // Session-Audio_<Date>
        }
        else {
            Log.i(TAG, "Asked to construct a filename for an unsupported media type: $mimeType.")
            "${context.getString(R.string.app_name)}$formattedDate.$fileExtension" // Session_<Date> - potentially no file extension, but it's the best we can do with limited data
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
    fun getFilenameFromUri(context: Context, uri: Uri?, mimeType: String? = null, attachment: Attachment? = null): String {
        Exception().printStackTrace()

        var extractedFilename: String? = null

        if (uri != null) {
            Log.w("ACL", "Trying 1..")
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
                Log.w("ACL", "Trying 2..")
                extractedFilename = attemptUriPathExtraction(uri.path!!)
            }
        }

        // Uri filename extraction failed - synthesize a filename from the media's MIME type.
        // Note: Giphy picked GIFs will use this to get a filename like "Session-GIF-<Date>" - but pre-saved GIFs
        // chosen via the file-picker or similar will use the existing saved filename as they will be caught in
        // the filename extraction code above.
        if (extractedFilename.isNullOrEmpty()) {
            Log.w("ACL", "Trying 3.. uriPath is: " + uri?.path)

            val haveFileIdFormattedPath = uri?.path?.contains("/file/") == true


            if (attachment == null) {
                Log.w("ACL", "The attachment is null!")
                val timestamp = if (uri?.path.isNullOrEmpty()) null else getTimestampFromUri(uri.path!!)
                extractedFilename = constructFallbackMediaFilenameFromMimeType(context, mimeType, timestamp)
            } else {

                Log.w("ACL", "We HAVE an attachment - the mime type is: " + mimeType)

                if (mimeType?.contains("audio") == true) {
                    extractedFilename = constructAudioMessageFilenameFromAttachment(context, attachment)
                } else {
                    Log.w("ACL", "Nah - gotta give it NOW as the timestamp")
                    extractedFilename = constructFallbackMediaFilenameFromMimeType(context, mimeType, null)
                }
            }

        }

        Log.w("ACL", "Returning with: " + extractedFilename)
        return extractedFilename!!
    }

    private fun attemptUriPathExtraction(uriPath: String): String? {
        // Split the path by "/" then traverse the segments in reverse order looking for the first one containing a dot
        val segments = uriPath.split("/")
        val extractedFilename = segments.asReversed().firstOrNull { it.contains('.') }

        // If found, return the identified filename, otherwise we'll be returning null
        return extractedFilename
    }

    // Uri paths comes in a variety of formats - if we have the right format we can extract the incoming file timestamp from it
    private fun getTimestampFromUri(uriPath: String): Long? {
        val segments = uriPath.split("/")

        // We cannot extract a timestamp from a uri path like "/file/6921609917390343" because that large number is not a timestamp
        val uriPathStartsWithFile = uriPath.startsWith("/file/") == true
        if (uriPathStartsWithFile) return null

        // If we have a uri path in a format like "/part/1736914338425/4" then we CAN extract that timestamp (the middle value)
        val uriPathStartsWithPart = uriPath.startsWith("/part/") == true
        if (!uriPathStartsWithPart) return null
        return try {
            segments.getOrNull(2)?.toLong()
        } catch (e: Exception){
            null
        }
    }
}