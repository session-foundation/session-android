package org.session.libsession.messaging.file_server

import okhttp3.HttpUrl
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileServerApi @Inject constructor() {

    companion object {
        const val MAX_FILE_SIZE = 10_000_000 // 10 MB

        val DEFAULT_FILE_SERVER: FileServer = FileServer(
            url = "http://filev2.getsession.org",
            ed25519PublicKeyHex = "b8eef9821445ae16e2e97ef8aa6fe782fd11ad5253cd6723b281341dba22e371"
        )


        fun buildAttachmentUrl(
            fileId: String,
            fileServer: FileServer,
            usesDeterministicEncryption: Boolean
        ): HttpUrl {
            val urlFragment = sequenceOf(
                "d".takeIf { usesDeterministicEncryption },
                if (!fileServer.url.isOfficial || fileServer.ed25519PublicKeyHex != DEFAULT_FILE_SERVER.ed25519PublicKeyHex) {
                    "p=${fileServer.ed25519PublicKeyHex}"
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
                        fileServer = FileServer(url = fileServerUrl, ed25519PublicKeyHex = fileServerPubKeyHex),
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

                fileServerUrl.isOfficial -> {
                    // We don't have a public key, but given it's an official file server,
                    // we can use the default public key
                    return URLParseResult(
                        fileId = id,
                        fileServer = FileServer(
                            url = fileServerUrl,
                            ed25519PublicKeyHex = DEFAULT_FILE_SERVER.ed25519PublicKeyHex
                        ),
                        usesDeterministicEncryption = deterministicEncryption
                    )
                }

                else -> {
                    // We don't have a public key, and it's not the default file server
                    throw Error.InvalidURL
                }
            }
        }
    }

    sealed class Error(message: String) : Exception(message) {
        object ParsingFailed    : Error("Invalid response.")
        object InvalidURL       : Error("Invalid URL.")
        object NoEd25519KeyPair : Error("Couldn't find ed25519 key pair.")
    }

    data class URLParseResult(
        val fileId: String,
        val fileServer: FileServer,
        val usesDeterministicEncryption: Boolean
    )



    data class UploadResult(
        val fileId: String,
        val fileUrl: String,
        val expires: ZonedDateTime?
    )

}