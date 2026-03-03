package org.thoughtcrime.securesms.conversation.v3

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities

/**
 * Formats message text for Compose rendering.
 *
 * Responsibilities:
 * - Adds URL string annotations ("url") + underline style (tap handling is done in the Composable).
 * - Bolds mentions and adds mention metadata annotations.
 * - Adds a "mention_bg" annotation for mentions that require a rounded background.
 * - Inserts subtle *layout spacing* immediately OUTSIDE mention_bg mentions so the rounded background
 *   doesn't visually butt against neighboring words.
 *
 * Non-responsibilities:
 * - No click handling
 * - No theme colors
 * - No UI behavior
 *
 * Safe to call in mappers: deterministic, context-free, no closures.
 */
object RichTextFormatter {

    private val URL_REGEX = Regex("""(?i)\bhttps?://[^\s<>()]+\b""")
    //todo convov3 look at new way to match urls

    // Subtle spacing that affects layout but is visually minimal.
    // If too subtle, try '\u2009' (thin space).
    private const val OUTSIDE_SPACE: Char = '\u200A' // hair space

    /**
     * Formats parsed message text into an AnnotatedString suitable for RichText().
     *
     * @param parsed Result of MentionUtilities.parseAndSubstituteMentions
     * @param isOutgoing Whether the message is outgoing
     */
    fun formatMessage(
        parsed: MentionUtilities.ParsedMentions,
        isOutgoing: Boolean
    ): AnnotatedString {
        val input = parsed.text
        val mentionsIn = parsed.mentions
            .sortedBy { it.start } // ensure deterministic left-to-right processing

        // 1) Build output text and remap mention ranges safely.
        val remapped = buildTextWithOutsideSpacing(
            text = input,
            mentions = mentionsIn,
            needsBg = { it.isSelf && !isOutgoing }
        )

        val outText = remapped.text
        val outMentions = remapped.mentions

        // 2) Build AnnotatedString with styles/annotations.
        val b = AnnotatedString.Builder(outText)

        // URLs: underline + "url" annotation (click handled in composable)
        URL_REGEX.findAll(outText).forEach { match ->
            val start = match.range.first
            val rawUrl = match.value
            val (url, _) = trimTrailingUrlPunctuation(rawUrl)
            val endExclusive = start + url.length

            b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, endExclusive)
            b.addStringAnnotation(tag = "url", annotation = url, start = start, end = endExclusive)
        }

        // Mentions: bold + metadata + bg marker (ranges are correct in outText)
        outMentions.forEach { m ->
            b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.start, m.endExclusive)
            b.addStringAnnotation("mention_pk", m.publicKey, m.start, m.endExclusive)
            b.addStringAnnotation("mention_self", m.isSelf.toString(), m.start, m.endExclusive)
            if (m.needsBg) b.addStringAnnotation("mention_bg", "true", m.start, m.endExclusive)
        }

        return b.toAnnotatedString()
    }

    // ---------------------------------------------------------------------
    // Safe range remapping
    // ---------------------------------------------------------------------

    private data class RemappedText(
        val text: String,
        val mentions: List<MentionOut>
    )

    private data class MentionOut(
        val start: Int,
        val endExclusive: Int,
        val publicKey: String,
        val isSelf: Boolean,
        val needsBg: Boolean
    )

    /**
     * Builds the final text left-to-right and produces new mention ranges.
     * This is the only robust way to insert extra characters without corrupting ranges.
     */
    private fun buildTextWithOutsideSpacing(
        text: String,
        mentions: List<MentionUtilities.MentionToken>,
        needsBg: (MentionUtilities.MentionToken) -> Boolean
    ): RemappedText {
        val out = StringBuilder(text.length + mentions.size * 2)
        val outMentions = ArrayList<MentionOut>(mentions.size)

        var cursor = 0

        for (m in mentions) {
            val start = m.start
            val end = m.endExclusive

            // Guard (mentions should be non-overlapping; if they aren't, we skip safely)
            if (start < cursor || start > text.length || end > text.length || start >= end) {
                continue
            }

            // Append text before mention
            if (cursor < start) out.append(text, cursor, start)

            val bg = needsBg(m)

            // Insert OUTSIDE_SPACE before mention if bg
            if (bg) out.append(OUTSIDE_SPACE)

            val mentionStartOut = out.length
            out.append(text, start, end)
            val mentionEndOut = out.length

            // Insert OUTSIDE_SPACE after mention if bg
            if (bg) out.append(OUTSIDE_SPACE)

            outMentions += MentionOut(
                start = mentionStartOut,
                endExclusive = mentionEndOut,
                publicKey = m.publicKey,
                isSelf = m.isSelf,
                needsBg = bg
            )

            cursor = end
        }

        // Append trailing text after last mention
        if (cursor < text.length) out.append(text, cursor, text.length)

        return RemappedText(text = out.toString(), mentions = outMentions)
    }

    // ---------------------------------------------------------------------
    // URL helpers
    // ---------------------------------------------------------------------

    /**
     * Removes common trailing punctuation from detected URLs.
     * Example: "https://example.com)." => url="https://example.com", trailing=")."
     */
    private fun trimTrailingUrlPunctuation(url: String): Pair<String, String> {
        if (url.isEmpty()) return url to ""

        val trailingChars = ".,;:!?)]}\"'"
        var end = url.length
        while (end > 0 && trailingChars.indexOf(url[end - 1]) >= 0) end--

        if (end == 0) return url to ""
        return url.substring(0, end) to url.substring(end)
    }
}