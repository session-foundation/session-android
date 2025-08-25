package org.session.libsignal.utilities

import android.annotation.SuppressLint
import android.net.http.X509TrustManagerExtensions
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager


object HTTP {
    var isConnectedToNetwork: (() -> Boolean) = { false }

    private val seedNodeClient: HttpClient by lazy {
        HttpClient(CIO)
    }

    private val serviceNodeClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                https {
                    @SuppressLint("CustomX509TrustManager")
                    trustManager = object : X509TrustManager {
                        @SuppressLint("TrustAllX509TrustManager")
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }

                        @SuppressLint("TrustAllX509TrustManager")
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }

                        override fun getAcceptedIssuers(): Array<X509Certificate> { return arrayOf() }
                    }
                }

                requestTimeout = TimeUnit.SECONDS.toMillis(timeout)
            }
        }
    }

    private const val timeout: Long = 120

    open class HTTPRequestFailedException(
        val statusCode: Int,
        val json: Map<*, *>?,
        message: String = "HTTP request failed with status code $statusCode"
    ) : kotlin.Exception(message)
    class HTTPNoNetworkException : HTTPRequestFailedException(0, null, "No network connection")

    enum class Verb(val rawValue: String) {
        GET("GET"), PUT("PUT"), POST("POST"), DELETE("DELETE")
    }

    /**
     * Sync. Don't call from the main thread.
     */
    suspend fun execute(verb: Verb, url: String, timeout: Long = HTTP.timeout, useSeedNodeConnection: Boolean = false): ByteArray {
        return execute(verb = verb, url = url, body = null, timeout = timeout, useSeedNodeConnection = useSeedNodeConnection)
    }

    /**
     * Sync. Don't call from the main thread.
     */
    suspend fun execute(verb: Verb, url: String, parameters: Map<String, Any>?, timeout: Long = HTTP.timeout, useSeedNodeConnection: Boolean = false): ByteArray {
        return if (parameters != null) {
            val body = JsonUtil.toJson(parameters).toByteArray()
            execute(verb = verb, url = url, body = body, timeout = timeout, useSeedNodeConnection = useSeedNodeConnection)
        } else {
            execute(verb = verb, url = url, body = null, timeout = timeout, useSeedNodeConnection = useSeedNodeConnection)
        }
    }

    /**
     * Sync. Don't call from the main thread.
     */
    suspend fun execute(verb: Verb, url: String, body: ByteArray?, timeout: Long = HTTP.timeout, useSeedNodeConnection: Boolean = false): ByteArray {
        val client = if (useSeedNodeConnection) seedNodeClient else serviceNodeClient

        try {
            val response = client.request(url) {
                method = HttpMethod.parse(verb.rawValue)

                headers {
                    remove("User-Agent")
                    remove("Accept-Language")

                    append("User-Agent", "WhatsApp")
                    append("Accept-Language", "en-us")
                }

                if (verb == Verb.PUT || verb == Verb.POST) {
                    check(body != null) { "Invalid request body." }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                timeout {
                    requestTimeoutMillis = TimeUnit.SECONDS.toMillis(timeout)
                }
            }

            when (val code = response.status) {
                HttpStatusCode.OK -> return response.bodyAsBytes()
                else -> {
                    Log.d("Loki", "${verb.rawValue} request to $url failed with status code: $code.")
                    throw HTTPRequestFailedException(code.value, null)
                }
            }

        } catch (exception: Exception) {
            Log.d("Loki", "${verb.rawValue} request to $url failed due to error: ${exception.localizedMessage}.", exception)

            if (!isConnectedToNetwork()) { throw HTTPNoNetworkException() }

            // Override the actual error so that we can correctly catch failed requests in OnionRequestAPI
            throw HTTPRequestFailedException(0, null, "HTTP request failed due to: ${exception.message}")
        }
    }
}
