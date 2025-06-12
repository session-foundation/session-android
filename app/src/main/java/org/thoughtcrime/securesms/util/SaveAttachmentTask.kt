package org.thoughtcrime.securesms.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.task.ProgressDialogAsyncTask
import org.session.libsignal.utilities.ExternalStorageUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.showSessionDialog
import java.util.Locale

/**
 * Saves attachment files to an external storage using [MediaStore] API.
 * Requires [android.Manifest.permission.WRITE_EXTERNAL_STORAGE] on API 28 and below.
 */
class SaveAttachmentTask @JvmOverloads constructor(context: Context, count: Int = 1) :
    ProgressDialogAsyncTask<SaveAttachmentTask.Attachment, Void, Pair<Int, String?>>(
        context,
        context.resources.getString(R.string.saving),
        context.resources.getString(R.string.saving)
    ) {

    companion object {
        @JvmStatic
        private val TAG = SaveAttachmentTask::class.simpleName

        private const val RESULT_SUCCESS = 0
        private const val RESULT_FAILURE = 1

        @JvmStatic
        @JvmOverloads
        fun showOneTimeWarningDialogOrSave(context: Context, count: Int = 1, onAcceptListener: () -> Unit = {}) {
            // If we've already warned the user that saved attachments can be accessed by other apps
            // then we'll just perform the save..
            val haveWarned = TextSecurePreferences.getHaveWarnedUserAboutSavingAttachments(context)
            if (haveWarned) {
                onAcceptListener()
            } else {
                // .. otherwise we'll show a warning dialog and only save if the user accepts the
                // potential risks of other apps accessing their saved attachments.
                context.showSessionDialog {
                    title(R.string.warning)
                    text(context.getString(R.string.attachmentsWarning))
                    dangerButton(R.string.save) {
                        // Set our 'haveWarned' SharedPref and perform the save on accept
                        TextSecurePreferences.setHaveWarnedUserAboutSavingAttachments(context)
                        onAcceptListener()
                    }
                    button(R.string.cancel)
                }
            }
        }

        fun saveAttachment(context: Context, attachment: Attachment): String? {
            val contentType = checkNotNull(MediaUtil.getJpegCorrectedMimeTypeIfRequired(attachment.contentType))
            var filename = attachment.filename
            Log.i(TAG, "Saving attachment as: $filename")

            val outputUri: Uri = getMediaStoreContentUriForType(contentType)
            val mediaUri = createOutputUri(context, outputUri, contentType, filename)

            val updateValues = ContentValues()
            PartAuthority.getAttachmentStream(context, attachment.uri).use { inputStream ->
                if (inputStream == null) {
                    return null
                }
                if (outputUri.scheme == ContentResolver.SCHEME_FILE) {
                    FileOutputStream(mediaUri!!.path).use { outputStream ->
                        StreamUtil.copy(inputStream, outputStream)
                        MediaScannerConnection.scanFile(context, arrayOf(mediaUri.path), arrayOf(contentType), null)
                    }
                } else {
                    context.contentResolver.openOutputStream(mediaUri!!, "w").use { outputStream ->
                        val total: Long = StreamUtil.copy(inputStream, outputStream)
                        if (total > 0) {
                            updateValues.put(MediaStore.MediaColumns.SIZE, total)
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= 29) {
                updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            }

            if (updateValues.size() > 0) {
                try {
                    context.contentResolver.update(mediaUri!!, updateValues, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update MediaStore entry", e)
                }
            }

            return outputUri.lastPathSegment
        }

        private fun getMediaStoreContentUriForType(contentType: String): Uri {
            return when {
                contentType.startsWith("video/") ->
                    ExternalStorageUtil.getVideoUri()
                contentType.startsWith("audio/") ->
                    ExternalStorageUtil.getAudioUri()
                contentType.startsWith("image/") ->
                    ExternalStorageUtil.getImageUri()
                else ->
                    ExternalStorageUtil.getDownloadUri()
            }
        }

        private fun createOutputUri(context: Context, outputUri: Uri, contentType: String, filename: String): Uri? {
            // Break the filename up into its base and extension in case we have to number the base should a file
            // with the given filename exist. e.g., "cat.jpg" --> base = "cat", extension = "jpg"
            val fileParts: Array<String> = getFileNameParts(filename)
            val base = fileParts[0]
            val extension = fileParts[1].lowercase(Locale.getDefault())

            // Some files (Giphy GIFs, for example) turn up as just a number with no file extension - so we'll use the contentType as
            // the mimetype for those & all others are picked up via `getMimeTypeFromExtension`
            val mimeType = if (extension.isEmpty()) contentType else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            contentValues.put(MediaStore.MediaColumns.DATE_ADDED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            contentValues.put(MediaStore.MediaColumns.DATE_MODIFIED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            if (Build.VERSION.SDK_INT > 28) {
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else if (outputUri.scheme == ContentResolver.SCHEME_FILE) {
                val outputDirectory = File(outputUri.path)
                var outputFile = File(outputDirectory, filename)

                // Find a unique filename by appending numbers rather than overwriting any existing file
                var i = 0
                while (outputFile.exists()) {
                    outputFile = File(outputDirectory, base + "-" + ++i + "." + extension)
                }

                if (outputFile.isHidden) {
                    throw IOException("Specified name would not be visible")
                }
                return Uri.fromFile(outputFile)
            } else {
                var outputFileName = filename
                var dataPath = String.format("%s/%s", getExternalPathToFileForType(context, contentType), outputFileName)

                // Find a unique filename by appending numbers rather than overwriting any existing file
                var i = 0
                while (pathTaken(context, outputUri, dataPath)) {
                    Log.d(TAG, "The content exists. Rename and check again.")
                    outputFileName = base + "-" + ++i + "." + extension
                    dataPath = String.format("%s/%s", getExternalPathToFileForType(context, contentType), outputFileName)
                }

                contentValues.put(MediaStore.MediaColumns.DATA, dataPath)
            }

            // making sure the inferred mimy type matches the destination collection
            var targetUri = outputUri
            if ((targetUri == ExternalStorageUtil.getImageUri() && (mimeType == null || !mimeType.startsWith("image/"))) ||
                (targetUri == ExternalStorageUtil.getVideoUri() && (mimeType == null || !mimeType.startsWith("video/"))) ||
                (targetUri == ExternalStorageUtil.getAudioUri() && (mimeType == null || !mimeType.startsWith("audio/")))) {
                Log.w(TAG, "MIME type $mimeType does not match target collection $targetUri, using Downloads collection instead.")
                targetUri = ExternalStorageUtil.getDownloadUri()
            }

            return context.contentResolver.insert(targetUri, contentValues)
        }

        private fun getExternalPathToFileForType(context: Context, contentType: String): String {
            val storage: File = when {
                contentType.startsWith("video/") ->
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                contentType.startsWith("audio/") ->
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!
                contentType.startsWith("image/") ->
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
                else ->
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
            }
            return storage.absolutePath
        }

        private fun getFileNameParts(fileName: String): Array<String> {
            val tokens = fileName.split("\\.(?=[^\\.]+$)".toRegex()).toTypedArray()
            return arrayOf(tokens[0], if (tokens.size > 1) tokens[1] else "")
        }

        private fun pathTaken(context: Context, outputUri: Uri, dataPath: String): Boolean {
            context.contentResolver.query(outputUri, arrayOf(MediaStore.MediaColumns.DATA),
                MediaStore.MediaColumns.DATA + " = ?", arrayOf(dataPath),
                null).use { cursor ->
                if (cursor == null) {
                    throw IOException("Something is wrong with the filename to save")
                }
                return cursor.moveToFirst()
            }
        }
    }

    private val contextReference = WeakReference(context)
    private val attachmentCount: Int = count

    @Deprecated("Deprecated in Java")
    override fun doInBackground(vararg attachments: Attachment?): Pair<Int, String?> {
        if (attachments.isEmpty()) {
            throw IllegalArgumentException("Must pass in at least one attachment")
        }

        try {
            val context = contextReference.get()
            var directory: String? = null

            if (context == null) {
                return Pair(RESULT_FAILURE, null)
            }

            for (attachment in attachments) {
                if (attachment != null) {
                    directory = saveAttachment(context, attachment)
                    if (directory == null) return Pair(RESULT_FAILURE, null)
                }
            }

            return if (attachments.size > 1)
                Pair(RESULT_SUCCESS, null)
            else
                Pair(RESULT_SUCCESS, directory)
        } catch (e: IOException) {
            Log.w(TAG, e)
            return Pair(RESULT_FAILURE, null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPostExecute(result: Pair<Int, String?>) {
        super.onPostExecute(result)
        val context = contextReference.get() ?: return

        when (result.first) {
            RESULT_FAILURE -> {
                val message = context.resources.getString(R.string.attachmentsSaveError)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }

            RESULT_SUCCESS -> {
                val message = context.resources.getString(R.string.saved)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }

            else -> throw IllegalStateException("Unexpected result value: " + result.first)
        }
    }

    data class Attachment(val uri: Uri, val contentType: String, val date: Long, val filename: String)
}