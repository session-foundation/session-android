package org.thoughtcrime.securesms.conversation.v2.utilities

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import network.loki.messenger.R
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.mention.MentionEditable
import org.thoughtcrime.securesms.conversation.v2.mention.MentionViewModel
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.util.RoundedBackgroundSpan
import org.thoughtcrime.securesms.util.getAccentColor
import java.util.regex.Pattern

/**
 * The result of highlighting mentions in a text.
 *
 * @param text The text with mentions replaced by display names and optionally styled.
 * @param mentions The recipients that were mentioned, in order of appearance.
 */
data class HighlightedMentionsResult(
    val text: SpannableString,
    val mentions: List<Pair<IntRange, Recipient>>,
)

object MentionUtilities {

    private val ACCOUNT_ID = Regex("@([0-9a-fA-F]{66})")
    private val pattern by lazy { Pattern.compile(ACCOUNT_ID.pattern) }

    /**
     * In-place replacement on the *live* MentionEditable that the
     * input-bar is already using.
     *
     * It swaps every "@<64-hex>" token for "@DisplayName" **and**
     * attaches a MentionSpan so later normalisation still works.
     */
    fun substituteIdsInPlace(
        editable: MentionEditable,
        membersById: Map<String, MentionViewModel.Member>
    ) {
        ACCOUNT_ID.findAll(editable)
            .toList()                // avoid index shifts
            .asReversed()            // back-to-front replacement
            .forEach { m ->
                val id      = m.groupValues[1]
                val member  = membersById[id] ?: return@forEach

                val start = m.range.first
                val end   = m.range.last + 1    // inclusive ➜ exclusive

                editable.replace(start, end, "@${member.name}")
                editable.addMention(member, start .. start + member.name.length + 1)
            }
    }

    /**
     * Look for mentions in a given text
     *
     * @return a Sequence of found mentioned AccountID and its range inside the text
     */
    fun CharSequence.findMentions(): Sequence<Pair<AccountId, IntRange>> {
        return sequence {
            val matcher = pattern.matcher(this@findMentions)
            while (matcher.find()) {
                val accountId = AccountId(matcher.group(1)!!)
                yield(accountId to matcher.start()..<matcher.end())
            }
        }
    }


    /**
     * Highlights mentions in a given text.
     *
     * @param text The text to highlight mentions in.
     * @param isOutgoingMessage Whether the message is outgoing.
     * @param isQuote Whether the message is a quote.
     * @param formatOnly Whether to only format the mentions. If true we only format the text itself,
     * for example resolving an accountID to a username. If false we also apply styling, like colors and background.
     * @param context The context to use.
     * @return A SpannableString with highlighted mentions.
     */
    @JvmStatic
    fun highlightMentions(
        recipientRepository: RecipientRepository,
        text: CharSequence,
        isOutgoingMessage: Boolean = false,
        isQuote: Boolean = false,
        formatOnly: Boolean = false,
        context: Context
    ): HighlightedMentionsResult {
        val foundMentions = text.findMentions().toList()
        if (foundMentions.isEmpty()) {
            return HighlightedMentionsResult(SpannableString(text), emptyList())
        }

        // Replace back-to-front to preserve earlier indices
        var replaced = text.toString()
        val mentions = mutableListOf<Pair<IntRange, Recipient>>()

        for ((accountId, range) in foundMentions.asReversed()) {
            val user = recipientRepository.getRecipientSync(accountId.toAddress())
            val userDisplayName = if (user.isSelf) {
                context.getString(R.string.you)
            } else {
                user.displayName(attachesBlindedId = true)
            }

            val mention = "@$userDisplayName"
            replaced = replaced.substring(0, range.first) + mention + replaced.substring(range.last + 1)
            mentions.add(0, range.first..<range.first + mention.length to user)
        }

        val result = SpannableString(replaced)

        // apply styling if required
        // Normal text color: black in dark mode and primary text color for light mode
        val mainTextColor by lazy {
            if (ThemeUtil.isDarkTheme(context)) context.getColor(R.color.black)
            else context.getColorFromAttr(android.R.attr.textColorPrimary)
        }

        // Highlighted text color: primary/accent in dark mode and primary text color for light mode
        val highlightedTextColor by lazy {
            if (ThemeUtil.isDarkTheme(context)) context.getAccentColor()
            else context.getColorFromAttr(android.R.attr.textColorPrimary)
        }

        if(!formatOnly) {
            for (mention in mentions) {
                val backgroundColor: Int?
                val foregroundColor: Int?

                // quotes
                if(isQuote) {
                    backgroundColor = null
                    // the text color has different rule depending if the message is incoming or outgoing
                    foregroundColor = if(isOutgoingMessage) null else highlightedTextColor
                }
                // incoming message mentioning you
                else if (mention.second.isSelf) {
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
                    // accent color for dark themes and primary text for light
                    foregroundColor = highlightedTextColor
                }

                // apply the background, if any
                backgroundColor?.let { background ->
                    result.setSpan(
                        RoundedBackgroundSpan(
                            context = context,
                            textColor = mainTextColor,
                            backgroundColor = background
                        ),
                        mention.first.first, mention.first.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // apply the foreground, if any
                foregroundColor?.let {
                    result.setSpan(
                        ForegroundColorSpan(it),
                        mention.first.first,
                        mention.first.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // apply bold on the mention
                result.setSpan(
                    StyleSpan(Typeface.BOLD),
                    mention.first.first,
                    mention.first.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return HighlightedMentionsResult(
            text = result,
            mentions = mentions,
        )
    }
}