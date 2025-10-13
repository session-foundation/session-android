package org.session.libsession.messaging.file_server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class FileServerApiTest {


    private data class Case(
        val name: String,
        val url: HttpUrl,
        val successfulParseResult: FileServerApi.URLParseResult?,
    )

    @Test
    fun `can build and parse attachment url`() {
        val api = FileServerApi(storage = mock())

        val testCases = listOf(
            Case(
                name = "With deterministic flag",
                url = "http://fileserver/file/id1#d&p=1234".toHttpUrl(),
                successfulParseResult = FileServerApi.URLParseResult(
                    fileId = "id1",
                    usesDeterministicEncryption = true,
                    fileServer = FileServer(
                        url = "http://fileserver".toHttpUrl(),
                        publicKeyHex = "1234"
                    )
                )
            ),
            Case(
                name = "With deterministic flag variant1",
                url = "http://fileserver/file/id1#d=&p=1234".toHttpUrl(),
                successfulParseResult = FileServerApi.URLParseResult(
                    fileId = "id1",
                    usesDeterministicEncryption = true,
                    fileServer = FileServer(
                        url = "http://fileserver".toHttpUrl(),
                        publicKeyHex = "1234"
                    )
                )
            ),
            Case(
                name = "Without deterministic flag",
                url = "http://fileserver/file/id1#p=1234".toHttpUrl(),
                successfulParseResult = FileServerApi.URLParseResult(
                    fileId = "id1",
                    usesDeterministicEncryption = false,
                    fileServer = FileServer(
                        url = "http://fileserver".toHttpUrl(),
                        publicKeyHex = "1234"
                    )
                )
            ),
            Case(
                name = "Official server without public key",
                url = "http://${FileServerApi.DEFAULT_FILE_SERVER.url.host}/file/id1".toHttpUrl(),
                successfulParseResult = FileServerApi.URLParseResult(
                    fileId = "id1",
                    usesDeterministicEncryption = false,
                    fileServer = FileServerApi.DEFAULT_FILE_SERVER
                ),
            ),

            // Error cases
            Case(
                name = "Missing file id",
                url = "http://fileserver/file/#d&p=1234".toHttpUrl(),
                successfulParseResult = null
            ),
            Case(
                name = "Missing public key",
                url = "http://fileserver/file/id1#d".toHttpUrl(),
                successfulParseResult = null
            ),
        )

        for (case in testCases) {
            try {
                val result = runCatching { api.parseAttachmentUrl(case.url) }
                if (case.successfulParseResult != null) {
                    val actual = result.getOrThrow()
                    assertEquals("Parse result differs!",case.successfulParseResult, actual)

                    val url = api.buildAttachmentUrl(actual.fileId, actual.fileServer, actual.usesDeterministicEncryption)
                    val reversed = api.parseAttachmentUrl(url)
                    assertEquals("Build URL differs!", actual, reversed)

                } else {
                    assertTrue("Parse result differs!", result.isFailure)
                }
            } catch (e: Exception) {
                throw RuntimeException("Case failed: ${case.name}", e)
            }
        }
    }
}