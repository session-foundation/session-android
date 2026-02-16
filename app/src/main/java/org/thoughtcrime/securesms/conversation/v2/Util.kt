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

import android.text.Spannable
import android.text.SpannableString
import android.text.style.URLSpan
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkType
import org.session.libsignal.utilities.Log
import java.util.EnumSet

object Util {
    private val TAG: String = Log.tag(Util::class.java)

    private val autoLinkExtractor: LinkExtractor = LinkExtractor.builder()
        .linkTypes(EnumSet.of(LinkType.URL, LinkType.WWW))
        .build()

    /**
     * Returns half of the difference between the given length, and the length when scaled by the
     * given scale.
     */
    fun halfOffsetFromScale(length: Int, scale: Float): Float {
        val scaledLength = length * scale
        return (length - scaledLength) / 2
    }

    /**
     * Uses autolink-java to detect URLs with better boundaries than Android Linkify,
     * and applies standard URLSpan spans only.
     *
     * Also returns TRUE if any links were created
     */
    fun Spannable.addUrlSpansWithAutolink() : Boolean {
        // Remove any existing URLSpans first so we don't get overlapping links
        getSpans(0, length, URLSpan::class.java).forEach { removeSpan(it) }

        // extract the links
        val text = toString()
        val links = autoLinkExtractor.extractLinks(text)

        // iterate detected links and keep only those that represent real links
        for (link in links) {
            // This is the exact range autolink detected
            val start = link.beginIndex
            val end = link.endIndex
            val raw = text.substring(start, end)

            val url = when (link.type) {
                LinkType.WWW -> "https://$raw"
                else -> raw
            }

            setSpan(URLSpan(url), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return links.any()
    }
}