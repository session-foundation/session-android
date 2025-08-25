package org.session.libsession.utilities

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Log

object DownloadUtilities {
    /**
     * Downloads a file from the file server using the provided URL.
     *
     * This will assume the URL is a valid file server URL, and if not,
     */
    suspend fun downloadFromFileServer(urlAsString: String): FileServerApi.SendResponse {
        try {
            val url = urlAsString.toHttpUrl()
            require(url.host == FileServerApi.fileServerUrl.host) {
                "Invalid file server URL: $url"
            }
            val fileID = checkNotNull(url.pathSegments.lastOrNull()) {
                "No file ID found in URL: $url"
            }

            return FileServerApi.download(fileID).await()
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