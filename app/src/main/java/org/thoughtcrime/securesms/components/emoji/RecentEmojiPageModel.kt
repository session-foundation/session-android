package org.thoughtcrime.securesms.components.emoji

import android.net.Uri
import network.loki.messenger.R
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentEmojiPageModel @Inject constructor(
    private val prefs: PreferenceStorage,
) : EmojiPageModel {
    companion object {
        private val RECENTLY_USED_KEY = PreferenceKey.json<List<String>>("Recents")
        private val DEFAULT_EMOJIS_LIST = listOf(
            "\ud83d\ude02",
            "\ud83e\udd70",
            "\ud83d\ude22",
            "\ud83d\ude21",
            "\ud83d\ude2e",
            "\ud83d\ude08")
    }

    override fun getKey(): String = RECENTLY_USED_KEY.name
    override fun getIconAttr(): Int = R.attr.emoji_category_recent

    override fun getEmoji(): List<String> {
        return prefs[RECENTLY_USED_KEY] ?: DEFAULT_EMOJIS_LIST
    }

    override fun getDisplayEmoji(): List<Emoji> {
        return emoji.map { Emoji(it) }
    }

    override fun hasSpriteMap(): Boolean = false
    override fun getSpriteUri(): Uri? = null
    override fun isDynamic(): Boolean = true

    fun onEmojiUsed(emoji: String) {
        val current = this.emoji
        val existingIndex = current.indexOf(emoji)
        val updated = if (existingIndex == 0) {
            // Already at the front, no need to update.
            return
        } else if (existingIndex > 0) {
            ArrayDeque(current).apply {
                removeAt(existingIndex)
                addFirst(emoji)
            }
        } else {
            ArrayDeque(current).apply { addFirst(emoji) }
        }

        while (updated.size > 6) {
            updated.removeLast()
        }

        prefs[RECENTLY_USED_KEY] = updated
    }
}