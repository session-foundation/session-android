package org.thoughtcrime.securesms.conversation.v3

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkType
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import java.util.EnumSet

/**
 * Pure text formatting for message content:
 * - No UI colors (Compose layer handles those)
 * - No click behavior (Compose layer injects handlers)
 *
 * Produces an AnnotatedString with:
 * - LinkAnnotation.Clickable(tag = url) with underline style
 * - String annotations for mentions:
 *    "mention_pk"   = publicKey
 *    "mention_self" = presence-only, when mentioning local user
 *    "mention_bg"   = presence-only, when pill background is needed
 *
 * Inserts thin-space padding around pill mentions for visual breathing room.
 */
object MessageTextFormatter {

    private val linkExtractor: LinkExtractor = LinkExtractor.builder()
        .linkTypes(EnumSet.of(LinkType.URL, LinkType.WWW))
        .build()

    // Spacing used around the mention highlight bg
    private const val OUTSIDE_SPACE: Char = '\u2009'

    fun formatMessage(
        parsed: MentionUtilities.ParsedMentions,
        isOutgoing: Boolean
    ): AnnotatedString {
        // Insert spacing ONLY for bg mentions (incoming mentions of self)
        val remapped = buildTextWithOutsideSpacing(
            text = parsed.text,
            mentions = parsed.mentions.sortedBy { it.start },
            needsBg = { it.isSelf && !isOutgoing }
        )

        val text = remapped.text
        val mentions = remapped.mentions

        val b = AnnotatedString.Builder(text)

        // Links first (they key off final text indices)
        addLinkAnnotationsWithAutolink(b, text)

        // Mentions: bold + metadata annotations
        for (m in mentions) {
            b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.start, m.endExclusive)
            b.addStringAnnotation("mention_pk", m.publicKey, m.start, m.endExclusive)
            if (m.isSelf) b.addStringAnnotation("mention_self", "", m.start, m.endExclusive)
            if (m.needsBg) b.addStringAnnotation("mention_bg", "", m.start, m.endExclusive)
        }

        return b.toAnnotatedString()
    }

    // ------------ Link handling --------------------

    private fun addLinkAnnotationsWithAutolink(
        builder: AnnotatedString.Builder,
        text: String
    ) {
        val links = linkExtractor.extractLinks(text)

        val styles = TextLinkStyles(
            style = SpanStyle(textDecoration = TextDecoration.Underline)
        )

        for (link in links) {
            val start = link.beginIndex
            val end = link.endIndex
            if (start < 0 || end > text.length || start >= end) continue

            val raw = text.substring(start, end)
            val url = when (link.type) {
                LinkType.WWW -> "https://$raw"
                else -> raw
            }

            builder.addLink(
                clickable = LinkAnnotation.Clickable(
                    tag = url,
                    styles = styles,
                    linkInteractionListener = null
                ),
                start = start,
                end = end
            )
        }
    }

    // ---------------- Mention spacing + remap ----------------

    private data class RemappedText(val text: String, val mentions: List<MentionOut>)
    private data class MentionOut(
        val start: Int,
        val endExclusive: Int,
        val publicKey: String,
        val isSelf: Boolean,
        val needsBg: Boolean
    )

    /**
     * Mention ranges in the output exclude the inserted spaces.
     *
     * Rebuilds text left-to-right, inserting [OUTSIDE_SPACE] before/after
     * mentions that need a pill background.
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

            // Defensive: skip invalid/overlapping
            if (start < cursor || start < 0 || end > text.length || start >= end) continue

            // before mention
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

        if (cursor < text.length) out.append(text, cursor, text.length)

        return RemappedText(out.toString(), outMentions)
    }
}