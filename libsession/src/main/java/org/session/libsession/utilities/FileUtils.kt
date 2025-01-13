package org.session.libsession.utilities

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.session.libsignal.utilities.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.AssertionError
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object FileUtils {

    const val BAD_FILENAME = "bad_filename"

    @JvmStatic
    @Throws(IOException::class)
    fun getFileDigest(fin: FileInputStream): ByteArray? {
        try {
            val digest = MessageDigest.getInstance("SHA256")

            val buffer = ByteArray(4096)
            var read = 0

            while ((fin.read(buffer, 0, buffer.size).also { read = it }) != -1) {
                digest.update(buffer, 0, read)
            }

            return digest.digest()
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
    }

    @Throws(IOException::class)
    fun deleteDirectoryContents(directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) return

        val files = directory.listFiles()

        if (files != null) {
            for (file in files) {
                if (file.isDirectory()) deleteDirectory(file)
                else file.delete()
            }
        }
    }

    @Throws(IOException::class)
    fun deleteDirectory(directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return
        }
        deleteDirectoryContents(directory)
        directory.delete()
    }

    // Method to attempt to get a filename from a Uri.
    // Note: We typically (now) populate filenames from the file picker Uri - which will work - if
    // we are forced to attempt to obtain the filename from a Uri which does NOT come directly from
    // the file picker then it may or MAY NOT work - or it may work but we get a GUID or an int as
    // the filename rather than the actual filename like "cat.jpg" etc. In such a case returning
    // null from this method means that the calling code must construct a suitable placeholder filename.
    @JvmStatic
    fun getFilenameFromUri(context: Context, uri: Uri): String? {
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

        if (extractedFilename == null) {
            Log.w("FileUtils", "Failed to get filename from content resolver or Uri path")
            return BAD_FILENAME
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
