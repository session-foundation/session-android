package org.thoughtcrime.securesms.linkpreview

import android.annotation.SuppressLint
import android.text.SpannableString
import android.text.style.URLSpan
import androidx.core.text.HtmlCompat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.utilities.Util
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.Util.addUrlSpansWithAutolink
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

object LinkPreviewUtil {

    private val DOMAIN_PATTERN = Regex("^(https?://)?([^/]+).*$", RegexOption.IGNORE_CASE)
    private val ALL_ASCII_PATTERN = Regex("^[\\x00-\\x7F]*$", RegexOption.IGNORE_CASE)
    private val ALL_NON_ASCII_PATTERN = Regex("^[^\\x00-\\x7F]*$", RegexOption.IGNORE_CASE)

    private val OPEN_GRAPH_TAG_PATTERN = Regex(
        "<\\s*meta[^>]*property\\s*=\\s*\"\\s*og:([^\"]+)\"[^>]*/?\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val ARTICLE_TAG_PATTERN = Regex(
        "<\\s*meta[^>]*property\\s*=\\s*\"\\s*article:([^\"]+)\"[^>]*/?\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val OPEN_GRAPH_CONTENT_PATTERN =
        Regex("content\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val TITLE_PATTERN =
        Regex("<\\s*title[^>]*>(.*)<\\s*/title[^>]*>", RegexOption.IGNORE_CASE)
    private val FAVICON_PATTERN =
        Regex("<\\s*link[^>]*rel\\s*=\\s*\".*icon.*\"[^>]*>", RegexOption.IGNORE_CASE)
    private val FAVICON_HREF_PATTERN = Regex("href\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /**
     * @return All whitelisted URLs in the source text.
     */
    @JvmStatic
    fun findWhitelistedUrls(text: String): List<Link> {
        val spannable = SpannableString(text)
        val found = spannable.addUrlSpansWithAutolink()

        if (!found) {
            return emptyList()
        }

        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        val links = ArrayList<Link>(spans.size)

        for (span in spans) {
            val link = Link(span.url, spannable.getSpanStart(span))
            if (isValidLinkUrl(link.url)) {
                links.add(link)
            }
        }

        return links
    }

    /**
     * @return True if the host is valid.
     */
    @JvmStatic
    fun isValidLinkUrl(linkUrl: String?): Boolean {
        if (linkUrl.isNullOrEmpty()) return false

        val url = linkUrl.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && isLegalUrl(linkUrl)
    }

    /**
     * @return True if the top-level domain is valid.
     */
    @JvmStatic
    fun isValidMediaUrl(mediaUrl: String?): Boolean {
        if (mediaUrl.isNullOrEmpty()) return false

        val url = mediaUrl.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && isLegalUrl(mediaUrl)
    }

    @JvmStatic
    fun isLegalUrl(url: String): Boolean {
        val match = DOMAIN_PATTERN.matchEntire(url) ?: return false
        val domain = match.groupValues.getOrNull(2) ?: return false
        val cleanedDomain = domain.replace(".", "")

        return ALL_ASCII_PATTERN.matches(cleanedDomain) || ALL_NON_ASCII_PATTERN.matches(
            cleanedDomain
        )
    }

    @JvmStatic
    fun isValidMimeType(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val validExtensions = arrayOf(".jpg", ".png", ".gif", ".jpeg")

        // If there's no dot at all, allow it.
        if (!lower.contains('.')) return true

        return validExtensions.any { lower.endsWith(it) }
    }

    @JvmStatic
    fun parseOpenGraphFields(html: String?): OpenGraph {
        return parseOpenGraphFields(html) { encoded ->
            HtmlCompat.fromHtml(encoded, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        }
    }

    internal fun parseOpenGraphFields(html: String?, htmlDecoder: HtmlDecoder): OpenGraph {
        if (html == null) {
            return OpenGraph(emptyMap(), null, null)
        }

        val openGraphTags = HashMap<String, String>()

        fun extractTags(tagRegex: Regex) {
            tagRegex.findAll(html).forEach { match ->
                val fullTag = match.value
                val property = match.groupValues.getOrNull(1)
                if (!property.isNullOrEmpty()) {
                    val contentMatch = OPEN_GRAPH_CONTENT_PATTERN.find(fullTag)
                    val content = contentMatch?.groupValues?.getOrNull(1)
                    if (content != null) {
                        val decoded = htmlDecoder.fromEncoded(content)
                        // Store the lower-cased property name.
                        openGraphTags[property.lowercase()] = decoded
                    }
                }
            }
        }

        extractTags(OPEN_GRAPH_TAG_PATTERN)
        extractTags(ARTICLE_TAG_PATTERN)

        var htmlTitle = ""
        var faviconUrl = ""

        TITLE_PATTERN.find(html)?.let { titleMatch ->
            val title = titleMatch.groupValues.getOrNull(1)
            if (title != null) {
                htmlTitle = htmlDecoder.fromEncoded(title)
            }
        }

        FAVICON_PATTERN.find(html)?.let { faviconTagMatch ->
            val hrefMatch = FAVICON_HREF_PATTERN.find(faviconTagMatch.value)
            val href = hrefMatch?.groupValues?.getOrNull(1)
            if (href != null) {
                faviconUrl = href
            }
        }

        return OpenGraph(openGraphTags, htmlTitle, faviconUrl)
    }

    class OpenGraph(
        private val values: Map<String, String>,
        private val title: String?,
        private val imageUrl: String?
    ) {

        companion object {
            private const val KEY_TITLE = "title"
            private const val KEY_IMAGE_URL = "image"
            private const val KEY_PUBLISHED_TIME_1 = "published_time"
            private const val KEY_PUBLISHED_TIME_2 = "article:published_time"
            private const val KEY_MODIFIED_TIME_1 = "modified_time"
            private const val KEY_MODIFIED_TIME_2 = "article:modified_time"
        }

        fun getTitle(): String? {
            return Util.getFirstNonEmpty(values[KEY_TITLE], title)
        }

        fun getImageUrl(): String? {
            return Util.getFirstNonEmpty(values[KEY_IMAGE_URL], imageUrl)
        }

        private fun parseISO8601(date: String?): Long {
            if (date.isNullOrEmpty()) return -1L

            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
            return try {
                format.parse(date)?.time ?: -1L
            } catch (pe: ParseException) {
                Log.w("OpenGraph", "Failed to parse date.", pe)
                -1L
            }
        }

        @SuppressLint("ObsoleteSdkInt")
        fun getDate(): Long {
            val candidates = arrayOf(
                values[KEY_PUBLISHED_TIME_1],
                values[KEY_PUBLISHED_TIME_2],
                values[KEY_MODIFIED_TIME_1],
                values[KEY_MODIFIED_TIME_2]
            )

            for (c in candidates) {
                val t = parseISO8601(c)
                if (t > 0) return t
            }

            return 0L
        }
    }

    fun interface HtmlDecoder {
        fun fromEncoded(html: String): String
    }
}