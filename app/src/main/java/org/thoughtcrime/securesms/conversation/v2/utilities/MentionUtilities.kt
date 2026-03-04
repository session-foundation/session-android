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

    data class ParsedMentions(
        val text: CharSequence,
        val mentions: List<MentionToken>
    )

    /**
     * Shared core:
     * - replaces "@<66-hex>" with "@DisplayName"
     * - returns the final text + mention ranges (in that final text) + metadata
     *
     * This is UI-agnostic and is used by BOTH:
     * - legacy XML span formatting
     * - Compose rich text formatting
     */
    fun parseAndSubstituteMentions(
        recipientRepository: RecipientRepository,
        input: CharSequence,
        context: Context
    ): ParsedMentions {
        var matcher = pattern.matcher(input)
        var startIndex = 0

        if (matcher.find(startIndex)) {
            var text = input
            val mentions = mutableListOf<MentionToken>()
            val recipients = arrayMapOf<String, Recipient>()

            while (true) {
                val publicKey =
                    text.subSequence(matcher.start() + 1, matcher.end()).toString() // drop '@'

                val user = recipients.getOrPut(publicKey) {
                    recipientRepository.getRecipientSync(publicKey.toAddress())
                }

                val displayName = if (user.isSelf) {
                    context.getString(R.string.you)
                } else {
                    user.displayName(attachesBlindedId = true)
                }

                val replacement = "@$displayName"

                val newText = buildString(
                    text.length - (matcher.end() - matcher.start()) + replacement.length
                ) {
                    append(text.subSequence(0, matcher.start()))
                    append(replacement)
                    append(text.subSequence(matcher.end(), text.length))
                }

                val start = matcher.start()
                val endExclusive = start + replacement.length

                mentions += MentionToken(
                    start = start,
                    endExclusive = endExclusive,
                    publicKey = publicKey,
                    isSelf = user.isSelf
                )

                text = newText
                startIndex = endExclusive

                matcher = pattern.matcher(text)
                if (!matcher.find(startIndex)) break
            }

            return ParsedMentions(
                text = text,
                mentions = mentions
            )
        }

        return ParsedMentions(
            text = input,
            mentions = emptyList()
        )
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