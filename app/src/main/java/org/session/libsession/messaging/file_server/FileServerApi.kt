package org.session.libsession.messaging.file_server

import android.util.Base64
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.database.StorageProtocol
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class FileServerApi @Inject constructor(
    private val storage: StorageProtocol,
) {

    companion object {
        const val MAX_FILE_SIZE = 10_000_000 // 10 MB

        val DEFAULT_FILE_SERVER: FileServer by lazy {
            FileServer(
                url = "http://filev2.getsession.org",
                publicKeyHex = "da21e1d886c6fbaea313f75298bd64aab03a97ce985b46bb2dad9f2089c8ee59"
            )
        }
    }

    sealed class Error(message: String) : Exception(message) {
        object ParsingFailed    : Error("Invalid response.")
        object InvalidURL       : Error("Invalid URL.")
        object NoEd25519KeyPair : Error("Couldn't find ed25519 key pair.")
    }

    private data class Request(
        val fileServer: FileServer,
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

    private fun createBody(body: ByteArray?, parameters: Any?): RequestBody? {
        if (body != null) return body.toRequestBody(
            "application/octet-stream".toMediaType(),
            0,
            body.size
        )

        if (parameters == null) return null
        val parametersAsJSON = JsonUtil.toJson(parameters)
        return parametersAsJSON.toRequestBody("application/json".toMediaType())
    }


    private fun send(request: Request): Promise<SendResponse, Exception> {
        val urlBuilder = request.fileServer.url
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
            OnionRequestAPI.sendOnionRequest(
                request = requestBuilder.build(),
                server = request.fileServer.url.host,
                x25519PublicKey = request.fileServer.publicKeyHex
            ).map {
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
        usedDeterministicEncryption: Boolean,
        fileServer: FileServer = DEFAULT_FILE_SERVER,
        customExpiresDuration: Duration? = null
    ): Promise<UploadResult, Exception> {
        val request = Request(
            fileServer = fileServer,
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
                fileUrl = buildAttachmentUrl(
                    fileId = id,
                    fileServer = fileServer,
                    usesDeterministicEncryption = usedDeterministicEncryption
                ).toString(),
                expires = expiresEpochSeconds?.asEpochSeconds()
            )
        }
    }

    fun download(
        fileId: String,
        fileServer: FileServer = DEFAULT_FILE_SERVER
    ): Promise<SendResponse, Exception> {
        val request = Request(
            fileServer = fileServer,
            verb = HTTP.Verb.GET,
            endpoint = "file/$fileId"
        )
        return send(request)
    }

    fun buildAttachmentUrl(
        fileId: String,
        fileServer: FileServer,
        usesDeterministicEncryption: Boolean
    ): HttpUrl {
        val urlFragment = sequenceOf(
            "d".takeIf { usesDeterministicEncryption },
            if (fileServer != DEFAULT_FILE_SERVER) {
                "p=${fileServer.publicKeyHex}"
            } else {
                null
            }
        ).filterNotNull()
            .joinToString(separator = "&")

        return fileServer.url
            .newBuilder()
            .addPathSegment("file")
            .addPathSegment(fileId)
            .fragment(urlFragment.takeIf { it.isNotBlank() })
            .build()
    }

    data class URLParseResult(
        val fileId: String,
        val fileServer: FileServer,
        val usesDeterministicEncryption: Boolean
    )

    fun parseAttachmentUrl(url: HttpUrl): URLParseResult {
        check(url.pathSegments.size == 2) {
            "Invalid URL: requiring exactly 2 path segments"
        }

        check(url.pathSegments[0] == "file") {
            "Invalid URL: first path segment must be 'file'"
        }

        val id = url.pathSegments[1]
        check(id.isNotBlank()) {
            "Invalid URL: id must not be blank"
        }

        var deterministicEncryption = false
        var fileServerPubKeyHex: String? = null

        url.fragment
            .orEmpty()
            .splitToSequence('&')
            .forEach { fragment ->
                when {
                    fragment == "d" || fragment == "d=" -> deterministicEncryption = true
                    fragment.startsWith("p=", ignoreCase = true) -> {
                        fileServerPubKeyHex = fragment.substringAfter("p=").takeIf { it.isNotBlank() }
                    }
                }
            }

        val fileServerUrl = url.newBuilder()
            .removePathSegment(0) // remove "file"
            .removePathSegment(0) // remove id
            .fragment(null) // remove fragment
            .build()

        when {
            !fileServerPubKeyHex.isNullOrEmpty() -> {
                // We'll use the public key we get from the URL
                return URLParseResult(
                    fileId = id,
                    fileServer = FileServer(url = fileServerUrl, publicKeyHex = fileServerPubKeyHex),
                    usesDeterministicEncryption = deterministicEncryption
                )
            }

            fileServerUrl == DEFAULT_FILE_SERVER.url -> {
                // We'll use the default file server
                return URLParseResult(
                    fileId = id,
                    fileServer = DEFAULT_FILE_SERVER,
                    usesDeterministicEncryption = deterministicEncryption
                )
            }

            else -> {
                // We don't have a public key, and it's not the default file server
                throw Error.InvalidURL
            }
        }
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
    suspend fun getClientVersion(fileServer: FileServer = DEFAULT_FILE_SERVER): VersionData {
        // Generate the auth signature
        val secretKey =  storage.getUserED25519KeyPair()?.secretKey?.data
            ?: throw (Error.NoEd25519KeyPair)

        val blindedKeys = BlindKeyAPI.blindVersionKeyPair(secretKey)
        val timestamp = System.currentTimeMillis().milliseconds.inWholeSeconds //  The current timestamp in seconds
        val signature = BlindKeyAPI.blindVersionSign(secretKey, timestamp)

        // The hex encoded version-blinded public key with a 07 prefix
        val blindedPkHex = "07" + blindedKeys.pubKey.data.toHexString()

        val request = Request(
            fileServer = fileServer,
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