package org.thoughtcrime.securesms.api.http

import okhttp3.OkHttpClient
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager


private const val DEFAULT_TIMEOUT_SECONDS = 120L

const val HTTP_EXECUTOR_SEMAPHORE_NAME = "HttpExecutorSemaphore"

fun createSeedSnodeOkHttpClient(): OkHttpClient.Builder {
    return OkHttpClient().newBuilder()
        .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}

fun createRegularNodeOkHttpClient(): OkHttpClient.Builder {
    // Snode to snode communication uses self-signed certificates but clients can safely ignore this
    val trustManager = object : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }
        override fun getAcceptedIssuers(): Array<X509Certificate> { return arrayOf() }
    }
    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, arrayOf( trustManager ), SECURE_RANDOM)
    return OkHttpClient().newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}
