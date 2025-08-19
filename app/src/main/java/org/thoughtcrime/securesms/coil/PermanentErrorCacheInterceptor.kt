package org.thoughtcrime.securesms.coil

import androidx.collection.LruCache
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.getRootCause
import javax.inject.Inject

/**
 * An Coil [Interceptor] that caches the permanent errors for [RemoteFile]s.
 *
 * Without the memory cache, we will have to read local file every time to find out
 * if there's a permanent error for the [RemoteFile]. This memory cache allows us to do this
 * only once and have the result cached in memory for subsequent requests. This could
 * avoid flickering in the UI when we already know that a [RemoteFile] has a permanent error.
 */
class PermanentErrorCacheInterceptor @Inject constructor() : Interceptor {
    private val permanentErrors = LruCache<RemoteFile, NonRetryableException>(100)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data !is RemoteFile) {
            return chain.proceed()
        }

        val permanentError = permanentErrors[data]

        if (permanentError != null) {
            return ErrorResult(
                image = null,
                request = chain.request,
                throwable = permanentError,
            )
        }

        // Otherwise go ahead and try to fetch the image.
        val result = runCatching { chain.proceed() }
        val error = result.exceptionOrNull() ?: (result.getOrNull() as? ErrorResult)?.throwable

        val rootCause = error?.getRootCause<NonRetryableException>()
        if (rootCause != null) {
            // Cache the permanent error for this RemoteFile.
            permanentErrors.put(data, rootCause)
            Log.d(TAG, "Caching permanent error for $data")

            return ErrorResult(
                image = null,
                request = chain.request,
                throwable = rootCause,
            )
        }

        return result.getOrThrow()
    }

    companion object {
        private const val TAG = "PermanentErrorCacheInterceptor"
    }
}