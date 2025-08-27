package org.session.libsession.messaging.file_server

import android.util.Base64
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.util.DateUtils.Companion.asEpochSeconds
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object FileServerApi {

    private const val FILE_SERVER_PUBLIC_KEY = "da21e1d886c6fbaea313f75298bd64aab03a97ce985b46bb2dad9f2089c8ee59"
    const val FILE_SERVER_URL = "http://filev2.getsession.org"
    const val MAX_FILE_SIZE = 10_000_000 // 10 MB

    val fileServerUrl: HttpUrl by lazy { FILE_SERVER_URL.toHttpUrl() }

    val FILE_SERVER_FILE_URL_PATTERN: Pattern by lazy {
        Pattern.compile("^https?://filev2\\.getsession\\.org/files?/(.+)$", Pattern.CASE_INSENSITIVE)
    }

    sealed class Error(message: String) : Exception(message) {
        object ParsingFailed    : Error("Invalid response.")
        object InvalidURL       : Error("Invalid URL.")
        object NoEd25519KeyPair : Error("Couldn't find ed25519 key pair.")
    }

    data class Request(
            val verb: HTTP.Verb,
            val endpoint: String,
            val queryParameters: Map<String, String> = mapOf(),
            val parameters: Any? = null,
            val headers: Map<String, String> = mapOf(),
            val body: ByteArray? = null,
            /**
         * Always `true` under normal circumstances. You might want to disable
         * this when running over Lokinet.
         */
        val useOnionRouting: Boolean = true
    )

    fun getFileIdFromUrl(url: String): String? {
        val matcher = FILE_SERVER_FILE_URL_PATTERN.matcher(url)
        return if (matcher.matches()) {
            matcher.group(1)
        } else {
            null
        }
    }

    private fun createBody(body: ByteArray?, parameters: Any?): RequestBody? {
        if (body != null) return RequestBody.create("application/octet-stream".toMediaType(), body)
        if (parameters == null) return null
        val parametersAsJSON = JsonUtil.toJson(parameters)
        return RequestBody.create("application/json".toMediaType(), parametersAsJSON)
    }


    private fun send(request: Request): Promise<SendResponse, Exception> {
        val urlBuilder = fileServerUrl
            .newBuilder()
            .addPathSegments(request.endpoint)
        if (request.verb == HTTP.Verb.GET) {
            for ((key, value) in request.queryParameters) {
                urlBuilder.addQueryParameter(key, value)
            }
        }
        val requestBuilder = okhttp3.Request.Builder()
            .url(urlBuilder.build())
            .headers(request.headers.toHeaders())
        when (request.verb) {
            HTTP.Verb.GET -> requestBuilder.get()
            HTTP.Verb.PUT -> requestBuilder.put(createBody(request.body, request.parameters)!!)
            HTTP.Verb.POST -> requestBuilder.post(createBody(request.body, request.parameters)!!)
            HTTP.Verb.DELETE -> requestBuilder.delete(createBody(request.body, request.parameters))
        }
        return if (request.useOnionRouting) {
            OnionRequestAPI.sendOnionRequest(requestBuilder.build(), FILE_SERVER_URL, FILE_SERVER_PUBLIC_KEY).map {
                val body = it.body ?: throw Error.ParsingFailed

                SendResponse(
                    body = body,
                    headers = it.info["headers"] as? Map<String, String>
                )
            }.fail { e ->
                when (e) {
                    // No need for the stack trace for HTTP errors
                    is HTTP.HTTPRequestFailedException -> Log.e("Loki", "File server request failed due to error: ${e.message}")
                    else -> Log.e("Loki", "File server request failed", e)
                }
            }
        } else {
            Promise.ofFail(IllegalStateException("It's currently not allowed to send non onion routed requests."))
        }
    }

    fun upload(
        file: ByteArray,
        customExpiresDuration: Duration? = null
    ): Promise<UploadResult, Exception> {
        val request = Request(
            verb = HTTP.Verb.POST,
            endpoint = "file",
            body = file,
            headers = buildMap {
                put("Content-Disposition", "attachment")
                put("Content-Type", "application/octet-stream")
                if (customExpiresDuration != null) {
                    put("X-FS-TTL", customExpiresDuration.inWholeSeconds.toString())
                }
            }
        )
        return send(request).map { response ->
            val json = JsonUtil.fromJson(response.body, Map::class.java)
            val id = json["id"]!!.toString()
            val expiresEpochSeconds = (json.getOrDefault("expires", null) as? Number)?.toLong()

            UploadResult(
                fileId = id,
                fileUrl = fileServerUrl.newBuilder()
                    .addPathSegment("file")
                    .addPathSegments(id)
                    .build()
                    .toString(),
                expires = expiresEpochSeconds?.asEpochSeconds()
            )
        }
    }

    fun download(file: String): Promise<SendResponse, Exception> {
        val request = Request(verb = HTTP.Verb.GET, endpoint = "file/$file")
        return send(request)
    }

    /**
     * Returns the current version of session
     * This is effectively proxying (and caching) the response from the github release
     * page.
     *
     * Note that the value is cached and can be up to 30 minutes out of date normally, and up to 24
     * hours out of date if we cannot reach the Github API for some reason.
     *
     * https://github.com/session-foundation/session-file-server/blob/dev/doc/api.yaml#L119
     */
    suspend fun getClientVersion(): VersionData {
        // Generate the auth signature
        val secretKey =  MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()?.secretKey?.data
            ?: throw (Error.NoEd25519KeyPair)

        val blindedKeys = BlindKeyAPI.blindVersionKeyPair(secretKey)
        val timestamp = System.currentTimeMillis().milliseconds.inWholeSeconds //  The current timestamp in seconds
        val signature = BlindKeyAPI.blindVersionSign(secretKey, timestamp)

        // The hex encoded version-blinded public key with a 07 prefix
        val blindedPkHex = "07" + blindedKeys.pubKey.data.toHexString()

        val request = Request(
            verb = HTTP.Verb.GET,
            endpoint = "session_version",
            queryParameters = mapOf("platform" to "android"),
            headers = mapOf(
                "X-FS-Pubkey" to blindedPkHex,
                "X-FS-Timestamp" to timestamp.toString(),
                "X-FS-Signature" to Base64.encodeToString(signature, Base64.NO_WRAP)
            )
        )

        // transform the promise into a coroutine
        val result = send(request).await()

        // map out the result
        return JsonUtil.fromJson(result.body, Map::class.java).let {
            VersionData(
                statusCode = it["status_code"] as? Int ?: 0,
                version = it["result"] as? String ?: "",
                updated = it["updated"] as? Double ?: 0.0
            )
        }
    }

    data class UploadResult(
        val fileId: String,
        val fileUrl: String,
        val expires: ZonedDateTime?
    )

    data class SendResponse(
        val body: ByteArraySlice,
        val headers: Map<String, String>?
    ) {
        /**
         * The "expires" header's value if any
         */
        val expires: ZonedDateTime? by lazy {
            headers?.get("expires")?.let {
                 ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
            }
        }
    }
}