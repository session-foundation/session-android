package org.thoughtcrime.securesms.api.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.thoughtcrime.securesms.api.ApiExecutorContext

class OkHttpApiExecutor(
    private val client: OkHttpClient,
    private val semaphore: Semaphore,
) : HttpApiExecutor {
    override suspend fun send(ctx: ApiExecutorContext, dest: HttpUrl, req: Request): Response {
        return semaphore.withPermit {
            withContext(Dispatchers.IO) {
                client.newCall(req).execute()
            }
        }
    }
}