package org.session.libsession.utilities

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.ByteArraySlice.Companion.write
import java.io.File
import java.io.OutputStream

object DownloadUtilities {

    /**
     * Blocks the calling thread.
     */
    suspend fun downloadFile(destination: File, url: String) {
        var remainingAttempts = 2
        var exception: Exception? = null

        destination.outputStream().use { outputStream ->
            while (remainingAttempts > 0) {
                remainingAttempts -= 1

                try {
                    downloadFile(outputStream, url)
                    return  // return on success
                } catch (e: HTTP.HTTPRequestFailedException) {
                    if (e.statusCode == 404) {
                        throw NonRetryableException("404 response trying to download file: $url", e)
                    }
                    exception = e
                } catch (e: Exception) {
                    exception = e
                }
            }
        }

        throw exception ?: NonRetryableException("Couldn't download file: $url")
    }

    /**
     * Blocks the calling thread.
     */
    suspend fun downloadFile(outputStream: OutputStream, urlAsString: String) {
        val url = urlAsString.toHttpUrlOrNull()!!
        val fileID = url.pathSegments.last()
        try {
            val data = FileServerApi.download(fileID).await()
            withContext(Dispatchers.IO) {
                outputStream.write(data)
            }
        } catch (e: Exception) {
            when (e) {
                // No need for the stack trace for HTTP errors
                is HTTP.HTTPRequestFailedException -> Log.e("Loki", "Couldn't download attachment due to error: ${e.message}")
                else -> Log.e("Loki", "Couldn't download attachment", e)
            }
            throw e
        }
    }
}