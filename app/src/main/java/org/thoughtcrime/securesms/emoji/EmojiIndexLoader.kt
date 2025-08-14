package org.thoughtcrime.securesms.emoji

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.session.libsignal.utilities.JsonUtil
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
) : OnAppStartupComponent {
    override fun onPostAppStarted() {
        scope.launch {
            if (emojiSearchDb.query("face", 1).isEmpty()) {
                try {
                    application.assets.open("emoji/emoji_search_index.json").use { inputStream ->
                        val searchIndex = listOf(
                            *JsonUtil.fromJson(
                                inputStream,
                                Array<EmojiSearchData>::class.java
                            )
                        )
                        emojiSearchDb.setSearchIndex(searchIndex)
                    }
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