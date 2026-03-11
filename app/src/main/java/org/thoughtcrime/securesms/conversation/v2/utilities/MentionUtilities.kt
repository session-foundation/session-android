package org.thoughtcrime.securesms.conversation.v2.utilities

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.collection.arrayMapOf
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.displayName
import org.thoughtcrime.securesms.conversation.v2.mention.MentionEditable
import org.thoughtcrime.securesms.conversation.v2.mention.MentionViewModel
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.util.RoundedBackgroundSpan
import org.thoughtcrime.securesms.util.getAccentColor
import network.loki.messenger.R
import org.session.libsession.utilities.recipients.Recipient
import java.util.regex.Pattern

object MentionUtilities {

    private val ACCOUNT_ID = Regex("@([0-9a-fA-F]{66})")
    private val pattern by lazy { Pattern.compile(ACCOUNT_ID.pattern) }

    /**
     * In-place replacement on the *live* MentionEditable that the
     * input-bar is already using.
     */
    fun substituteIdsInPlace(
        editable: MentionEditable,
        membersById: Map<String, MentionViewModel.Member>
    ) {
        ACCOUNT_ID.findAll(editable)
            .toList()       // avoid index shifts
            .asReversed()   // back-to-front replacement
            .forEach { m ->
                val id = m.groupValues[1]
                val member = membersById[id] ?: return@forEach

                val start = m.range.first
                val end = m.range.last + 1 // inclusive ➜ exclusive

                editable.replace(start, end, "@${member.name}")
                editable.addMention(member, start..start + member.name.length + 1)
            }
    }

    // ----------------------------
    // Shared parsing/substitution core
    // ----------------------------

    data class MentionToken(
        val start: Int,          // start in FINAL substituted text
        val endExclusive: Int,   // end-exclusive in FINAL substituted text
        val publicKey: String,
        val isSelf: Boolean
    )

    data class SubstituteResult(
        val text: CharSequence,
        val mentions: List<MentionToken>
    )

    /**
     * Parse an input and returns all occurrence of mention syntax: `@id`. The range will include
     * the character '@'.
     *
     * @return null if no mention is found. Otherwise, returns a sequence of ranges of indices
     * where the mention syntax is found, including the character '@'.
     */
    fun parseMentions(input: CharSequence): Sequence<IntRange>? {
        val matcher = pattern.matcher(input)
        if (!matcher.find()) return null

        return generateSequence(matcher.start() until matcher.end()) {
            if (matcher.find()) matcher.start() until matcher.end() else null
        }
    }

    /**
     * Check if the content has mentions of the current user.
     */
    fun mentionsMe(content: CharSequence, recipientRepository: RecipientRepository): Boolean {
        return parseMentions(content)?.any { range ->
            val address = content.substring(range.first + 1, range.last + 1).toAddress()
            recipientRepository.fastIsSelf(address)
        } == true
    }

    /**
     * Shared core:
     * - replaces "@<66-hex>" with "@DisplayName"
     * - returns the final text + mention ranges (in that final text) + metadata
     *
     * This is UI-agnostic and is used by BOTH:
     * - legacy XML span formatting
     * - Compose rich text formatting
     */
    /**
     * Performs the text substitution given pre-parsed mention ranges, resolving each
     * public key via [recipientRepository].
     */
    fun substituteMentions(
        recipientRepository: RecipientRepository,
        input: CharSequence,
        mentionRanges: Sequence<IntRange>,
        context: Context
    ): SubstituteResult {
        val mentions = mutableListOf<MentionToken>()
        val recipients = arrayMapOf<String, Recipient>()
        val result = StringBuilder(input.length)
        var lastEnd = 0
        var offset = 0

        for (range in mentionRanges) {
            val publicKey = input.subSequence(range.first + 1, range.last + 1).toString() // drop '@'

            val user = recipients.getOrPut(publicKey) {
                recipientRepository.getRecipientSync(publicKey.toAddress())
            }

            val displayName = if (user.isSelf) context.getString(R.string.you)
                              else user.displayName(attachesBlindedId = true)
            val replacement = "@$displayName"

            result.append(input.subSequence(lastEnd, range.first))

            val start = range.first + offset
            result.append(replacement)

            mentions += MentionToken(
                start = start,
                endExclusive = start + replacement.length,
                publicKey = publicKey,
                isSelf = user.isSelf
            )

            offset += replacement.length - (range.last + 1 - range.first)
            lastEnd = range.last + 1
        }

        result.append(input.subSequence(lastEnd, input.length))

        return SubstituteResult(text = result, mentions = mentions)
    }

    fun parseAndSubstituteMentions(
        recipientRepository: RecipientRepository,
        input: CharSequence,
        context: Context
    ): SubstituteResult {
        val mentionRanges = parseMentions(input)
            ?: return SubstituteResult(text = input, mentions = emptyList())

        return substituteMentions(recipientRepository, input, mentionRanges, context)
    }

    // ----------------------------
    // Legacy (XML/TextView) formatter
    // ----------------------------

    /**
     * Legacy (XML/TextView) formatter.
     *
     * Highlights mentions in a given text.
     *
     * @param formatOnly If true we only format the text itself,
     * for example resolving an accountID to a username. If false we also apply styling.
     */
    fun highlightMentions(
        recipientRepository: RecipientRepository,
        text: CharSequence,
        isOutgoingMessage: Boolean = false,
        isQuote: Boolean = false,
        formatOnly: Boolean = false,
        context: Context
    ): SpannableString {
        val parsed = parseAndSubstituteMentions(recipientRepository, text, context)
        val result = SpannableString(parsed.text)

        if (formatOnly) return result

        // Normal text color: black in dark mode and primary text color for light mode
        val mainTextColor by lazy {
            if (ThemeUtil.isDarkTheme(context)) context.getColor(R.color.black)
            else context.getColorFromAttr(android.R.attr.textColorPrimary)
        }

        // Highlighted text color: accent in dark theme and primary text for light
        val highlightedTextColor by lazy {
            if (ThemeUtil.isDarkTheme(context)) context.getAccentColor()
            else context.getColorFromAttr(android.R.attr.textColorPrimary)
        }

        parsed.mentions.forEach { mention ->
            val backgroundColor: Int?
            val foregroundColor: Int?

            // quotes
            if (isQuote) {
                backgroundColor = null
                // incoming quote gets accent-ish foreground, outgoing quote keeps default
                foregroundColor = if (isOutgoingMessage) null else highlightedTextColor
            }
            // incoming message mentioning you
            else if (mention.isSelf && !isOutgoingMessage) {
                backgroundColor = context.getAccentColor()
                foregroundColor = mainTextColor
            }
            // outgoing message
            else if (isOutgoingMessage) {
                backgroundColor = null
                foregroundColor = mainTextColor
            }
            // incoming messages mentioning someone else
            else {
                backgroundColor = null
                foregroundColor = highlightedTextColor
            }

            val start = mention.start
            val end = mention.endExclusive

            backgroundColor?.let { background ->
                result.setSpan(
                    RoundedBackgroundSpan(
                        context = context,
                        textColor = mainTextColor,
                        backgroundColor = background
                    ),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            foregroundColor?.let { fg ->
                result.setSpan(
                    ForegroundColorSpan(fg),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            result.setSpan(
                StyleSpan(Typeface.BOLD),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return result
    }
}