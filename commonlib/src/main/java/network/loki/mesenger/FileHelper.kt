//FileHelper.kt
package network.loki.mesenger

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object FileHelper {

    /**
     * Lee todos los bytes de un Uri (archivo).
     * Retorna null si falla.
     */
    fun readAllBytesFromUri(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Escribe [data] en [uri].
     * Retorna true si OK, false si error.
     */
    fun writeAllBytesToUri(context: Context, uri: Uri, data: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(data)
                output.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Intenta obtener el nombre (filename) del Uri,
     * por ejemplo "miFoto.jpg".
     */
    fun getFilenameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        result = it.getString(idx)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.lastPathSegment
        }
        return result
    }
}
