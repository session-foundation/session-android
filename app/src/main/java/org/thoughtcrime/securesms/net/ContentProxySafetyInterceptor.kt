package org.thoughtcrime.securesms.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil.isValidLinkUrl
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil.isValidMediaUrl
import java.io.IOException

/**
 * Interceptor to do extra safety checks on requests through the [ContentProxySelector]
 * to prevent non-whitelisted requests from getting to it. In particular, this guards against
 * requests redirecting to non-whitelisted domains.
 *
 * Note that because of the way interceptors are ordered, OkHttp will hit the proxy with the
 * bad-redirected-domain before we can intercept the request, so we have to "look ahead" by
 * detecting a redirected response on the first pass.
 */
class ContentProxySafetyInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (isWhitelisted(chain.request().url)) {
            val response = chain.proceed(chain.request())

            if (response.isRedirect) {
                if (isWhitelisted(response.header("location")) || isWhitelisted(response.header("Location"))) {
                    return response
                } else {
                    Log.w(TAG, "Tried to redirect to a non-whitelisted domain!")
                    chain.call().cancel()
                    throw IOException("Tried to redirect to a non-whitelisted domain!")
                }
            } else {
                return response
            }
        } else {
            Log.w(TAG, "Request was for a non-whitelisted domain!")
            chain.call().cancel()
            throw IOException("Request was for a non-whitelisted domain!")
        }
    }

    companion object {
        private val TAG: String = Log.tag(ContentProxySafetyInterceptor::class.java)

        private fun isWhitelisted(url: HttpUrl): Boolean {
            return isWhitelisted(url.toString())
        }

        private fun isWhitelisted(url: String?): Boolean {
            return isValidLinkUrl(url) || isValidMediaUrl(url)
        }
    }
}
