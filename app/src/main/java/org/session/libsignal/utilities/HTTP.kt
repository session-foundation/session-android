package org.session.libsignal.utilities

import android.util.Log
import androidx.annotation.RawRes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import io.ktor.http.headers
import org.session.libsession.messaging.MessagingModuleConfiguration
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import network.loki.messenger.R


object HTTP {
    var isConnectedToNetwork: (() -> Boolean) = { false }

    private val seedNodeTrustManagers = sequenceOf("seed1" to R.raw.seed1, "seed2" to R.raw.seed2, "seed3" to R.raw.seed3)
        .associate { (name, resId) -> "$name.getsession.org" to lazy { createTrustManagerForPEM(resId) } }

    private fun createTrustManagerForPEM(@RawRes resId: Int): X509TrustManager {
        val certificates = MessagingModuleConfiguration.shared.context.resources.openRawResource(resId).use { inputStream ->
            CertificateFactory.getInstance("X.509").generateCertificates(inputStream)
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).also { keyStore ->
            keyStore.load(null, null)
            certificates.forEachIndexed { index, certificate ->
                keyStore.setCertificateEntry("ca$index", certificate)
            }
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        return trustManagerFactory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    private val client by lazy {
        HttpClient(CIO) {
            engine {
                // It is only for trusting self-signed certificates.
                https {
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authorization: String?) {
                            error("Client certificate not required")
                        }

                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authorization: String) {
                            seedNodeTrustManagers[authorization]?.value?.checkServerTrusted(chain, authorization)
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                    }
                }
            }
        }
    }

    private const val DEFAULT_TIMEOUT_SECONDS: Long = 120

    open class HTTPRequestFailedException(
        val statusCode: Int,
        val json: Map<*, *>?,
        message: String = "HTTP request failed with status code $statusCode"
    ) : Exception(message)
    class HTTPNoNetworkException : HTTPRequestFailedException(0, null, "No network connection")

    enum class Verb(val rawValue: String) {
        GET("GET"), PUT("PUT"), POST("POST"), DELETE("DELETE")
    }

    /**
     * Sync. Don't call from the main thread.
     */
    suspend fun execute(verb: Verb, url: String, timeout: Long = DEFAULT_TIMEOUT_SECONDS): ByteArray {
        return execute(verb = verb, url = url, body = null, timeoutSeconds = timeout)
    }

    /**
     * Sync. Don't call from the main thread.
     */
    suspend fun execute(
        verb: Verb,
        url: String,
        parameters: Map<String, Any>?,
        timeout: Long = DEFAULT_TIMEOUT_SECONDS
    ): ByteArray {
        return if (parameters != null) {
            val body = JsonUtil.toJson(parameters).toByteArray()
            execute(verb = verb, url = url, body = body, timeoutSeconds = timeout)
        } else {
            execute(verb = verb, url = url, body = null, timeoutSeconds = timeout)
        }
    }

    /**
     * Sync. Don't call from the main thread.
     */
    suspend fun execute(verb: Verb, url: String, body: ByteArray?, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): ByteArray {
        check(body == null || verb != Verb.GET) { "GET requests cannot have a body." }

        return try {
            val response = client.request(urlString = url) {
                method = HttpMethod(verb.rawValue)

                headers {
                    append("User-Agent", "WhatsApp")
                    append("Accept-Language", "en-us")

                    if (body != null) {
                        append("Content-Length", body.size.toString())
                        append("Content-Type", "application/json")
                    }
                }

                if (body != null) {
                    setBody(body)
                }

                timeout {
                    connectTimeoutMillis = timeoutSeconds * 1000
                }
            }

            val statusCode = response.status

            when (statusCode.value) {
                200 -> response.bodyAsBytes()
                else -> {
                    Log.d("Loki", "${verb.rawValue} request to $url failed with status code: $statusCode.")
                    throw HTTPRequestFailedException(statusCode.value, null)
                }
            }
        } catch (exception: Exception) {
            Log.d("Loki", "${verb.rawValue} request to $url failed due to error: ${exception.localizedMessage}.")

            if (!isConnectedToNetwork()) { throw HTTPNoNetworkException() }

            // Override the actual error so that we can correctly catch failed requests in OnionRequestAPI
            throw HTTPRequestFailedException(0, null, "HTTP request failed due to: ${exception.message}")
        }
    }
}
