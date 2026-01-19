package org.thoughtcrime.securesms.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class OkHttpApiExecutor @Inject constructor(
) : ApiExecutor<HttpUrl, Request, Response> {

    private val okHttpClient by lazy {
        val trustManager = object : X509TrustManager {

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }
            override fun getAcceptedIssuers(): Array<X509Certificate> { return arrayOf() }
        }
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, arrayOf( trustManager ), SECURE_RANDOM)
        OkHttpClient().newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .callTimeout(120, TimeUnit.SECONDS)
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun send(ctx: ApiExecutorContext, dest: HttpUrl, req: Request): Response {
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(req).execute()
        }
    }
}