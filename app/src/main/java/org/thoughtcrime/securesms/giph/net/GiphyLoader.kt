package org.thoughtcrime.securesms.giph.net

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.giph.model.GiphyImage
import org.thoughtcrime.securesms.giph.model.GiphyResponse
import org.thoughtcrime.securesms.net.ContentProxySelector
import org.thoughtcrime.securesms.util.AsyncLoader
import java.io.IOException

abstract class GiphyLoader(
    context: Context,
    private val searchString: String?
) : AsyncLoader<List<GiphyImage>>(context) {

    companion object {
        private val TAG = GiphyLoader::class.java.simpleName
        @JvmField var PAGE_SIZE: Int = 100
    }

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .proxySelector(ContentProxySelector())
            .build()

    @VisibleForTesting
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override fun loadInBackground(): List<GiphyImage> = loadPage(0)

    fun loadPage(offset: Int): List<GiphyImage> {
        return try {
            val url =
                if (TextUtils.isEmpty(searchString)) {
                    String.format(getTrendingUrl(), offset)
                } else {
                    String.format(getSearchUrl(), offset, Uri.encode(searchString))
                }

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) return emptyList()

                val giphyResponse: GiphyResponse = json.decodeFromString(body)
                giphyResponse.data
            }
        } catch (e: Exception) {
            Log.w(TAG, e)
            emptyList()
        }
    }

    protected abstract fun getTrendingUrl(): String
    protected abstract fun getSearchUrl(): String
}
