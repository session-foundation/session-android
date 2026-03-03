package org.thoughtcrime.securesms.conversation.v3

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkType
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import java.util.EnumSet

/**
 * Formats message text for Compose rendering.
 *
 * Responsibilities:
 * - Detects links using autolink-java (URL + WWW) with robust boundaries
 * - Adds URL string annotations ("url") + underline style (tap handling in Composable)
 * - Bolds mentions and adds mention metadata annotations
 * - Adds "mention_bg" annotation for mentions that require rounded background
 * - Inserts subtle *layout spacing* immediately OUTSIDE mention_bg mentions, without corrupting
 *   other mention ranges (safe remap via left-to-right rebuild)
 *
 * Non-responsibilities:
 * - No click handling
 * - No theme colors
 * - No UI behavior
 *
 * Safe to call in mappers.
 */
object RichTextFormatter {

    // autolink-java: better link boundaries than regex / Linkify
    private val linkExtractor: LinkExtractor = LinkExtractor.builder()
        .linkTypes(EnumSet.of(LinkType.URL, LinkType.WWW))
        .build()

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

        // 1) Rebuild text with outside spacing around bg mentions and remap mention ranges safely.
        val remapped = buildTextWithOutsideSpacing(
            text = input,
            mentions = parsed.mentions.sortedBy { it.start },
            needsBg = { it.isSelf && !isOutgoing }
        )

        val outText = remapped.text
        val outMentions = remapped.mentions

        // 2) Create AnnotatedString and apply link + mention annotations/styles.
        val b = AnnotatedString.Builder(outText)

        // Links: underline + "url" annotation
        addLinkAnnotationsWithAutolink(b, outText)

        // Mentions: bold + metadata + bg marker
        outMentions.forEach { m ->
            b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.start, m.endExclusive)
            b.addStringAnnotation("mention_pk", m.publicKey, m.start, m.endExclusive)
            b.addStringAnnotation("mention_self", m.isSelf.toString(), m.start, m.endExclusive)
            if (m.needsBg) b.addStringAnnotation("mention_bg", "true", m.start, m.endExclusive)
        }

        return b.toAnnotatedString()
    }

    // ---------------------------------------------------------------------
    // Links (Compose annotations) via autolink-java
    // ---------------------------------------------------------------------

    /**
     * Uses autolink-java to detect URL/WWW links and applies:
     * - underline style
     * - "url" string annotations containing the normalized url (WWW -> https://...)
     */
    private fun addLinkAnnotationsWithAutolink(
        builder: AnnotatedString.Builder,
        text: String
    ) {
        val links = linkExtractor.extractLinks(text)

        for (link in links) {
            val start = link.beginIndex
            val end = link.endIndex

            if (start < 0 || end > text.length || start >= end) continue

            val raw = text.substring(start, end)
            val url = when (link.type) {
                LinkType.WWW -> "https://$raw"
                else -> raw
            }

            builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
                start,
                end
            )
            builder.addStringAnnotation(
                tag = "url",
                annotation = url,
                start = start,
                end = end
            )
        }
    }

    // ---------------------------------------------------------------------
    // Safe mention spacing + range remapping
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
     * This is the robust way to insert extra characters without breaking indices.
     *
     * We insert OUTSIDE_SPACE immediately before and after mention text for mentions that need bg.
     * The mention range excludes this spacing so the rounded pill hugs the mention text, while
     * the spacing provides "breathing room" to neighbors.
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

            // Defensive: skip invalid/overlapping ranges
            if (start < cursor || start < 0 || end > text.length || start >= end) continue

            // Append text before mention
            if (cursor < start) out.append(text, cursor, start)

            val bg = needsBg(m)

            if (bg) out.append(OUTSIDE_SPACE)

            val mentionStartOut = out.length
            out.append(text, start, end)
            val mentionEndOut = out.length

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

        // Append trailing text
        if (cursor < text.length) out.append(text, cursor, text.length)

        return RemappedText(out.toString(), outMentions)
    }
}