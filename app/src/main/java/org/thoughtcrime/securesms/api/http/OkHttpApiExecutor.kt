package org.thoughtcrime.securesms.api.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsignal.utilities.ByteArraySlice.Companion.toRequestBody
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.ApiExecutorContext

class OkHttpApiExecutor(
    private val client: OkHttpClient,
    private val semaphore: Semaphore,
) : HttpApiExecutor {
    override suspend fun send(ctx: ApiExecutorContext, req: HttpRequest): HttpResponse {
        return semaphore.withPermit {
            withContext(Dispatchers.IO) {
                Log.d("OkHttpApiExecutor", "Sending request: ${req.url}")
                client.newCall(req.toOkHttpRequest()).execute().toHttpResponse()
            }
        }
    }

    private fun HttpRequest.toOkHttpRequest(): okhttp3.Request {
        val httpBody = when (body) {
            is HttpBody.Text -> body.text.toRequestBody()
            is HttpBody.Bytes -> body.bytes.toRequestBody()
            is HttpBody.ByteSlice -> body.slice.toRequestBody()
            null -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, httpBody)

        for ((key, value) in this.headers) {
            builder.addHeader(key, value)
        }

        return builder.build()
    }

    private fun okhttp3.Response.toHttpResponse(): HttpResponse {
        val bytes = body.bytes()

        // Can we convert it to text?
        val text = if (body.contentType()?.type == "text" ||
            body.contentType()?.subtype == "json" ||
            body.contentType()?.subtype == "xml"
        ) {
            runCatching {
                bytes.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        } else {
            null
        }

        return HttpResponse(
            statusCode = this.code,
            headers = headers.toMap(),
            body = if (text != null) {
                HttpBody.Text(text)
            } else {
                HttpBody.Bytes(bytes)
            }
        )
    }
}