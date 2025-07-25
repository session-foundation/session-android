package org.thoughtcrime.securesms.conversation.v2.utilities

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Range
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import nl.komponents.kovenant.combine.Tuple2
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.truncateIdForDisplay
import org.thoughtcrime.securesms.conversation.v2.mention.MentionEditable
import org.thoughtcrime.securesms.conversation.v2.mention.MentionViewModel
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.util.RoundedBackgroundSpan
import org.thoughtcrime.securesms.util.getAccentColor
import java.util.regex.Pattern

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
     * Highlights mentions in a given text.
     *
     * @param text The text to highlight mentions in.
     * @param isOutgoingMessage Whether the message is outgoing.
     * @param isQuote Whether the message is a quote.
     * @param formatOnly Whether to only format the mentions. If true we only format the text itself,
     * for example resolving an accountID to a username. If false we also apply styling, like colors and background.
     * @param threadID The ID of the thread the message belongs to.
     * @param context The context to use.
     * @return A SpannableString with highlighted mentions.
     */
    @JvmStatic
    fun highlightMentions(
        text: CharSequence,
        isOutgoingMessage: Boolean = false,
        isQuote: Boolean = false,
        formatOnly: Boolean = false,
        threadID: Long,
        context: Context
    ): SpannableString {
        @Suppress("NAME_SHADOWING") var text = text

        var matcher = pattern.matcher(text)
        val mentions = mutableListOf<Tuple2<Range<Int>, String>>()
        var startIndex = 0
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)!!
        val openGroup by lazy { DatabaseComponent.get(context).storage().getOpenGroup(threadID) }

        // Format the mention text
        if (matcher.find(startIndex)) {
            while (true) {
                val publicKey = text.subSequence(matcher.start() + 1, matcher.end()).toString() // +1 to get rid of the @

                val isYou = isYou(publicKey, userPublicKey, openGroup)
                val userDisplayName: String? = if (isYou) {
                    context.getString(R.string.you)
                } else {
                    val contact = DatabaseComponent.get(context).sessionContactDatabase().getContactWithAccountID(publicKey)
                    @Suppress("NAME_SHADOWING") val context = if (openGroup != null) Contact.ContactContext.OPEN_GROUP else Contact.ContactContext.REGULAR
                    contact?.displayName(context)
                }
                if (userDisplayName != null) {
                    val mention = "@$userDisplayName"
                    text = text.subSequence(0, matcher.start()).toString() + mention + text.subSequence(matcher.end(), text.length)
                    val endIndex = matcher.start() + 1 + userDisplayName.length
                    startIndex = endIndex
                    mentions.add(Tuple2(Range.create(matcher.start(), endIndex), publicKey))
                } else {
                    startIndex = matcher.end()
                }
                matcher = pattern.matcher(text)
                if (!matcher.find(startIndex)) { break }
            }
        }
        val result = SpannableString(text)

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
                else if (isYou(mention.second, userPublicKey, openGroup)) {
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
                        mention.first.lower, mention.first.upper, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // apply the foreground, if any
                foregroundColor?.let {
                    result.setSpan(
                        ForegroundColorSpan(it),
                        mention.first.lower,
                        mention.first.upper,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // apply bold on the mention
                result.setSpan(
                    StyleSpan(Typeface.BOLD),
                    mention.first.lower,
                    mention.first.upper,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return result
    }

    private fun isYou(mentionedPublicKey: String, userPublicKey: String, openGroup: OpenGroup?): Boolean {
        val isUserBlindedPublicKey = openGroup?.let {
            BlindKeyAPI.sessionIdMatchesBlindedId(
                sessionId = userPublicKey,
                blindedId = mentionedPublicKey,
                serverPubKey = it.publicKey
            )
        } ?: false
        return mentionedPublicKey.equals(userPublicKey, ignoreCase = true) || isUserBlindedPublicKey
    }
}