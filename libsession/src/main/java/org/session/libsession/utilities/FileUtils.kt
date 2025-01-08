package org.session.libsession.utilities

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.AssertionError
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object FileUtils {
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

    // Method to attempt to get a filename from a URI - if there's already a non-null or empty filename we'll return that
    @JvmStatic
    fun extractFilenameFromUriIfRequired(context: Context, uri: Uri, filename: String?): String? {
        var extractedFilename = filename

        // Check if fileName is null/empty
        if (extractedFilename.isNullOrEmpty()) {
            // If we're dealing with a content URI, query the provider to get the actual file name
            if ("content".equals(uri.getScheme(), ignoreCase = true)) {
                val projection = arrayOf<String?>(OpenableColumns.DISPLAY_NAME)
                val cursor = context.getContentResolver().query(uri, projection, null, null, null)
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            extractedFilename = cursor.getString(nameIndex)
                        }
                    } finally {
                        cursor.close()
                    }
                }
            }

            // If we still don't have a name, fallback to the Uri path (hopefully something sane, but possibly just a number)
            if (extractedFilename == null || extractedFilename.isEmpty()) {
                extractedFilename = uri.getPath() // e.g. "/storage/emulated/0/Download/cat.jpg"
                if (extractedFilename != null) {
                    val cut = extractedFilename.lastIndexOf('/')
                    if (cut != -1) {
                        // Grab just the filename from the path
                        extractedFilename = extractedFilename.substring(cut + 1)
                    }
                }
            }
        }

        // If our final extracted filename is just a number then we'll return an empty string and Session
        // can call the file "session-<DateTime>.<FileExtension>" on save as a last resort.
        if (extractedFilename == null || extractedFilename.toIntOrNull() != null) {
            extractedFilename = ""
        }

        return extractedFilename
    }

}
