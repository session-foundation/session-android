/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.text.Spannable
import android.text.style.URLSpan
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.utilities.ModalURLSpan

object Util {
    private val TAG: String = Log.tag(Util::class.java)

    /**
     * Returns half of the difference between the given length, and the length when scaled by the
     * given scale.
     */
    fun halfOffsetFromScale(length: Int, scale: Float): Float {
        val scaledLength = length * scale
        return (length - scaledLength) / 2
    }

    fun Spannable.replaceUrlSpanWithModal(context: Context, urlSpan: URLSpan) {
        val start = getSpanStart(urlSpan)
        val end = getSpanEnd(urlSpan)
        val flags = getSpanFlags(urlSpan)

        // Clean up Linkify spans
        val cleaned = (this as CharSequence).cleanLinkifyUrlSpan(start, end, urlSpan.url)

        val replacementSpan = ModalURLSpan(cleaned.url) { url ->
            val activity = context as? ConversationActivityV2
            activity?.showOpenUrlDialog(url)
        }

        removeSpan(urlSpan)
        setSpan(replacementSpan, start, cleaned.end, flags)
    }

    private data class CleanUrlResult(val end: Int, val url: String)

    /**
     * Cleans up greedy Linkify spans by trimming
     * Example: "(https://example.com)Video" should link only "https://example.com".
     */
    private fun CharSequence.cleanLinkifyUrlSpan(start: Int, end: Int, originalUrl: String): CleanUrlResult {
        var trimmedEnd = end

        // 1) Trim common trailing punctuation that is almost never intended to be part of the URL.
        while (trimmedEnd > start) {
            val last = this[trimmedEnd - 1]
            if (last == '.' || last == ',' || last == ';' || last == ':' || last == '!' || last == '?' ||
                last == '"' || last == '\'' || last == '”' || last == '’' || last == '…') {
                trimmedEnd--
            } else {
                break
            }
        }

        // 2) If there is an unmatched closing bracket/paren/brace, cut the URL before it.
        run {
            var paren = 0
            var bracket = 0
            var brace = 0
            val candidate = this.subSequence(start, trimmedEnd)
            for (i in candidate.indices) {
                when (candidate[i]) {
                    '(' -> paren++
                    ')' -> {
                        if (paren == 0) {
                            trimmedEnd = start + i
                            return@run
                        }
                        paren--
                    }
                    '[' -> bracket++
                    ']' -> {
                        if (bracket == 0) {
                            trimmedEnd = start + i
                            return@run
                        }
                        bracket--
                    }
                    '{' -> brace++
                    '}' -> {
                        if (brace == 0) {
                            trimmedEnd = start + i
                            return@run
                        }
                        brace--
                    }
                }
            }
        }

        // 3) Parse. If parsing fails, trim character-by-character until it succeeds.
        var parsed = this.subSequence(start, trimmedEnd).toString().toHttpUrlOrNull()
        while (parsed == null && trimmedEnd > start) {
            trimmedEnd--
            parsed = this.subSequence(start, trimmedEnd).toString().toHttpUrlOrNull()
        }

        val finalUrl = parsed?.toString()
            ?: originalUrl.toHttpUrlOrNull()?.toString()
            ?: originalUrl

        return CleanUrlResult(end = if (parsed != null) trimmedEnd else end, url = finalUrl)
    }
}