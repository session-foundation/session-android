package org.thoughtcrime.securesms.emoji

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.EmojiSearchDatabase
import org.thoughtcrime.securesms.database.model.EmojiSearchData
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.io.IOException
import javax.inject.Inject

class EmojiIndexLoader @Inject constructor(
    private val application: Application,
    private val emojiSearchDb: EmojiSearchDatabase,
    @param:ManagerScope private val scope: CoroutineScope,
    private val json: Json,
) : OnAppStartupComponent {
    @OptIn(ExperimentalSerializationApi::class)
    override fun onPostAppStarted() {
        scope.launch {
            if (emojiSearchDb.query("face", 1).isEmpty()) {
                try {
                    val searchIndex: List<EmojiSearchData> =
                        application.assets.open("emoji/emoji_search_index.json")
                            .use(json::decodeFromStream)

                    emojiSearchDb.setSearchIndex(searchIndex)
                } catch (e: IOException) {
                    Log.e(
                        "EmojiIndexLoader",
                        "Failed to load emoji search index",
                        e
                    )
                }
            }
        }
    }
}