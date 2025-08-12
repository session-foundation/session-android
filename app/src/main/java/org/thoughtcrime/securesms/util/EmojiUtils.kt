package org.thoughtcrime.securesms.util

import android.text.Spannable
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.EmojiCompat.LOAD_STATE_SUCCEEDED
import androidx.emoji2.text.EmojiSpan
import kotlin.math.max
import kotlin.math.min

object EmojiUtils {

    /**
     * Checks if a Unicode codepoint is considered an emoji.
     */
    private fun isEmoji(codePoint: Int): Boolean {
        return (codePoint >= 0x1F000 && codePoint <= 0x1FFFF) ||  // Most emojis live here
                (codePoint >= 0x2000 && codePoint <= 0x2BFF) ||  // Includes arrows, symbols
                (codePoint >= 0x2300 && codePoint <= 0x23FF) ||  // Technical misc
                (codePoint >= 0x2600 && codePoint <= 0x27EF) ||  // Dingbats, misc symbols
                (codePoint >= 0x2900 && codePoint <= 0x297F) ||  // Supplemental arrows
                (codePoint >= 0x2B00 && codePoint <= 0x2BFF) ||  // More symbols
                (codePoint >= 0x3000 && codePoint <= 0x303F) ||  // CJK symbols
                (codePoint >= 0x3200 && codePoint <= 0x32FF) ||  // Enclosed CJK
                (codePoint >= 0xFE00 && codePoint <= 0xFE0F) ||  // Variation selectors
                (codePoint >= 0x1F900 && codePoint <= 0x1F9FF) ||  // Supplemental symbols
                (codePoint >= 0x1FA70 && codePoint <= 0x1FAFF) // Extended symbols
    }

    /**
     * Returns true if the text (ignoring whitespace) is only emojis.
     */
    private fun isAllEmojis(text: CharSequence?): Boolean {
        if (text == null || text.length == 0) return false
        val len = text.length
        var emojiCount = 0
        var offset = 0
        while (offset < len) {
            val codePoint = Character.codePointAt(text, offset)
            if (!Character.isWhitespace(codePoint)) {
                if (!isEmoji(codePoint)) {
                    return false
                }
                emojiCount++
            }
            offset += Character.charCount(codePoint)
        }
        return emojiCount > 0
    }

    /**
     * Counts the number of emoji codepoints in the text (ignoring whitespace).
     */
    private fun countEmojis(text: CharSequence?): Int {
        if (text == null || text.length == 0) return 0
        val len = text.length
        var emojiCount = 0
        var offset = 0
        while (offset < len) {
            val codePoint = Character.codePointAt(text, offset)
            if (!Character.isWhitespace(codePoint) && isEmoji(codePoint)) {
                emojiCount++
            }
            offset += Character.charCount(codePoint)
        }
        return emojiCount
    }

    /**
     * Checks if the given text only contains emojis by going through the spans
     * @param text
     * @return true if the text only contains emojis, false otherwise
     */
    private fun isTextOnlyEmojiUsingSpans(text: Spannable?): Boolean {
        if (text == null || text.length == 0) {
            // Depending on how you define "only emoji," empty text might be false or true.
            return false
        }

        val length = text.length
        val spans = text.getSpans(0, length, EmojiSpan::class.java)

        // If there are no spans at all, but the text isn't empty, it can't be all emoji
        if (spans == null || spans.size == 0) {
            return false
        }

        // Track coverage for each character
        val coverage = BooleanArray(length)
        for (span in spans) {
            val start = max(0.0, text.getSpanStart(span).toDouble()).toInt()
            val end = min(length.toDouble(), text.getSpanEnd(span).toDouble()).toInt()

            for (i in start..<end) {
                coverage[i] = true
            }
        }

        // Check if every character is covered
        for (covered in coverage) {
            if (!covered) {
                return false
            }
        }
        return true
    }

    /**
     * Returns the count of emojis if the text only consists of emojis, 0 otherwise
     */
    fun getOnlyEmojiCount(text: CharSequence): Int {
        // if we can rely on EmojiCompat, do that, as it is more precise
        if(EmojiCompat.get().getLoadState() == LOAD_STATE_SUCCEEDED){
            val processedText = EmojiCompat.get().process(text, 0, text.length, Integer.MAX_VALUE, EmojiCompat.REPLACE_STRATEGY_ALL)
            val allEmojis = processedText is Spannable && isTextOnlyEmojiUsingSpans(processedText)
            if(allEmojis) {
                val spannable = processedText as Spannable
                return spannable.getSpans(0, spannable.length, EmojiSpan::class.java).size
            }
            else return 0
        } else {
            // otherwise we use our local methods
            if(isAllEmojis(text)) return countEmojis(text)
            else return 0
        }
    }

}