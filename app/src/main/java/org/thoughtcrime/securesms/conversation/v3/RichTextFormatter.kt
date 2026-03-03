package org.thoughtcrime.securesms.conversation.v3

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities

object RichTextFormatter {

    private val URL_REGEX = Regex("""(?i)\bhttps?://[^\s<>()]+\b""")

    /**
     * Build an AnnotatedString for message text with:
     * - URLs made clickable using LinkAnnotation (no ClickableText)
     * - URL underline via TextLinkStyles
     * - Mentions bold + mention metadata + mention_bg tag where needed
     *
     * Notes:
     * - We build sequentially because LinkAnnotation can only be applied while appending (withLink).
     * - We do NOT apply colors here (leave to Compose theme).
     *
     * @param onUrlClick called when a URL is clicked. You decide how to open it.
     */
    fun formatMessage(
        parsed: MentionUtilities.ParsedMentions,
        isOutgoing: Boolean,
        onUrlClick: (String) -> Unit
    ): AnnotatedString {
        val text = parsed.text

        val base = buildAnnotatedString {
            var cursor = 0

            URL_REGEX.findAll(text).forEach { match ->
                var start = match.range.first
                var endExclusive = match.range.last + 1
                var urlText = match.value

                // Trim common trailing punctuation from URL matches so "https://x.y)." doesn't include ")."
                val (trimmedUrl, trailing) = trimTrailingUrlPunctuation(urlText)
                if (trimmedUrl != urlText) {
                    endExclusive = start + trimmedUrl.length
                    urlText = trimmedUrl
                }

                // Append non-url chunk
                if (cursor < start) append(text.substring(cursor, start))

                // Append URL chunk with link
                val link = LinkAnnotation.Clickable(
                    tag = "url",
                    linkInteractionListener = { onUrlClick(urlText) },
                    styles = TextLinkStyles(
                        style = SpanStyle(textDecoration = TextDecoration.Underline)
                    )
                )
                withLink(link) { append(urlText) }

                // Append trailing punctuation that we trimmed off
                if (trailing.isNotEmpty()) append(trailing)

                cursor = match.range.last + 1 // move past the ORIGINAL match
            }

            // Append remaining text
            if (cursor < text.length) append(text.substring(cursor))
        }

        // Now add mention styles/annotations by range.
        // Mention ranges are based on parsed.text; since we rebuilt the same text content in order,
        // indices still line up.
        val b = AnnotatedString.Builder(base)

        parsed.mentions.forEach { m ->
            val start = m.start
            val end = m.endExclusive

            // Bold mentions (parity)
            b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)

            // Metadata tags (optional but handy)
            b.addStringAnnotation("mention_pk", m.publicKey, start, end)
            b.addStringAnnotation("mention_self", m.isSelf.toString(), start, end)

            // Tag when mention needs rounded background:
            // parity with XML: incoming mentioning you
            val needsBg = m.isSelf && !isOutgoing
            if (needsBg) {
                b.addStringAnnotation("mention_bg", "true", start, end)
            }
        }

        return b.toAnnotatedString()
    }

    private fun trimTrailingUrlPunctuation(url: String): Pair<String, String> {
        if (url.isEmpty()) return url to ""

        // Common trailing punctuation we don't want inside URL
        val trailingChars = ".,;:!?)]}\"'"
        var end = url.length
        while (end > 0 && trailingChars.indexOf(url[end - 1]) >= 0) end--

        // If we trimmed everything, keep original
        if (end == 0) return url to ""

        val trimmed = url.substring(0, end)
        val trailing = url.substring(end)
        return trimmed to trailing
    }
}