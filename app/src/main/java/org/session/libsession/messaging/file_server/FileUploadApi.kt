package org.session.libsession.messaging.file_server

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.network.ServerClientErrorManager
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.util.DateUtils.Companion.asEpochSeconds
import java.time.Duration

class FileUploadApi @AssistedInject constructor(
    @Assisted private val fileServer: FileServer,
    @Assisted private val data: ByteArray,
    @Assisted private val usedDeterministicEncryption: Boolean,
    @Assisted private val customExpiresDuration: Duration?,
    errorManager: ServerClientErrorManager,
    private val json: Json,
) : ServerApi<FileServerApi.UploadResult>(
    errorManager
) {
    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        check(fileServer.url.toString().startsWith(baseUrl)) {
            "FileServer URL ${fileServer.url} does not match base URL $baseUrl"
        }

        return HttpRequest(
            url = "$baseUrl/file".toHttpUrl(),
            method = "POST",
            headers = buildMap {
                put("Content-Disposition", "attachment")
                put("Content-Type", "application/octet-stream")
                if (customExpiresDuration != null) {
                    put("X-FS-TTL", customExpiresDuration.toSeconds().toString())
                }
            },
            body = HttpBody.Bytes(data),
        )
    }

    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): FileServerApi.UploadResult {
        @Suppress("OPT_IN_USAGE")
        val response = json.decodeFromStream<Response>(response.body.asInputStream())
        return FileServerApi.UploadResult(
            fileId = response.id,
            fileUrl = FileServerApi.buildAttachmentUrl(
                fileId = response.id,
                fileServer = fileServer,
                usesDeterministicEncryption = usedDeterministicEncryption
            ).toString(),
            expires = response.expiresEpochSeconds?.toLong()?.asEpochSeconds()
        )
    }

    @Serializable
    private class Response(
        val id: String,

        @SerialName("expires")
        val expiresEpochSeconds: Double?,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            fileServer: FileServer,
            data: ByteArray,
            usedDeterministicEncryption: Boolean,
            customExpiresDuration: Duration? = null,
        ): FileUploadApi
    }
}