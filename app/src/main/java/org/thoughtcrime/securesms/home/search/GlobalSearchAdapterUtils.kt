package org.thoughtcrime.securesms.home.search

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import network.loki.messenger.R
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.truncateIdForDisplay
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.ContentView
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.Model.GroupConversation
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.Model.Header
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.Model.Message
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.Model.SavedMessages
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.Model.SubHeader
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.SearchUtil
import java.util.Locale
import org.thoughtcrime.securesms.home.search.GlobalSearchAdapter.Model.Contact as ContactModel

class GlobalSearchDiff(
    private val oldQuery: String?,
    private val newQuery: String?,
    private val oldData: List<GlobalSearchAdapter.Model>,
    private val newData: List<GlobalSearchAdapter.Model>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldData.size
    override fun getNewListSize(): Int = newData.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        oldData[oldItemPosition] == newData[newItemPosition]

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        oldQuery == newQuery && oldData[oldItemPosition] == newData[newItemPosition]

    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
        if (oldQuery != newQuery) newQuery
        else null
}

private val BoldStyleFactory = { StyleSpan(Typeface.BOLD) }

fun ContentView.bindQuery(query: String, model: GlobalSearchAdapter.Model) {
    when (model) {
        is ContactModel -> {
            binding.resultTitle.setupTitleWithBadge(
                title = model.name,
                showProBadge = model.showProBadge
            )
        }
        is Message -> {
            val textSpannable = SpannableStringBuilder()
            if (model.messageResult.conversationRecipient != model.messageResult.messageRecipient) {
                // group chat, bind
                val text = "${model.messageResult.messageRecipient.searchName}: "
                textSpannable.append(text)
            }
            textSpannable.append(getHighlight(
                    query,
                    model.messageResult.bodySnippet
            ))
            binding.searchResultSubtitle.text = textSpannable
            binding.searchResultSubtitle.isVisible = true
            binding.resultTitle.setupTitleWithBadge(
                title =  model.messageResult.conversationRecipient.searchName,
                showProBadge = model.showProBadge
            )
        }
        is GroupConversation -> {
            binding.resultTitle.setupTitleWithBadge(
                title =  model.title,
                showProBadge = model.showProBadge
            )

            binding.searchResultSubtitle.text = getHighlight(query, model.legacyMembersString.orEmpty())
        }
        is Header,               // do nothing for header
        is SubHeader,            // do nothing for subheader
        is SavedMessages -> Unit // do nothing for saved messages (displays note to self)
    }
}

private fun getHighlight(query: String?, toSearch: String): Spannable? {
    return SearchUtil.getHighlightedSpan(Locale.getDefault(), BoldStyleFactory, toSearch, query)
}

private fun ComposeView.setupTitleWithBadge(title: String, showProBadge: Boolean){
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setThemedContent {
        ProBadgeText(
            text = title,
            textStyle = LocalType.current.h8.bold().copy(color = LocalColors.current.text),
            showBadge = showProBadge,
        )
    }
}

fun ContentView.bindModel(query: String?, model: GroupConversation) {
    binding.searchResultProfilePicture.isVisible = true
    binding.searchResultSubtitle.isVisible = model.isLegacy
    binding.searchResultTimestamp.isVisible = false
    val threadRecipient = MessagingModuleConfiguration.shared.recipientRepository.getRecipientSync(
        Address.fromSerialized(model.groupId)
    )

    binding.searchResultProfilePicture.setThemedContent {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = MessagingModuleConfiguration.shared.avatarUtils.getUIDataFromRecipient(threadRecipient)
        )
    }

    binding.resultTitle.setupTitleWithBadge(
        title =  model.title,
        showProBadge = model.showProBadge
    )

    if (model.legacyMembersString != null) {
        binding.searchResultSubtitle.text = getHighlight(query, model.legacyMembersString)
    }
}

fun ContentView.bindModel(query: String?, model: ContactModel) = binding.run {
    searchResultProfilePicture.isVisible = true
    searchResultSubtitle.isVisible = false
    searchResultTimestamp.isVisible = false
    searchResultSubtitle.text = null
    val recipient = MessagingModuleConfiguration.shared.recipientRepository.getRecipientSync(
        Address.fromSerialized(model.contact.hexString)
    )
    searchResultProfilePicture.setThemedContent {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = MessagingModuleConfiguration.shared.avatarUtils.getUIDataFromRecipient(recipient)
        )
    }

    val nameString = if (model.isSelf) root.context.getString(R.string.noteToSelf)
        else model.name

    binding.resultTitle.setupTitleWithBadge(
        title =  nameString,
        showProBadge = model.showProBadge
    )
}

fun ContentView.bindModel(model: SavedMessages) {
    binding.searchResultSubtitle.isVisible = false
    binding.searchResultTimestamp.isVisible = false
    binding.resultTitle.setupTitleWithBadge(
        title = binding.root.context.getString(R.string.noteToSelf),
        showProBadge = false
    )

    val recipient = MessagingModuleConfiguration.shared.recipientRepository.getSelf()

    binding.searchResultProfilePicture.setThemedContent {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = MessagingModuleConfiguration.shared.avatarUtils.getUIDataFromRecipient(recipient)
        )
    }
    binding.searchResultProfilePicture.isVisible = true
}

fun ContentView.bindModel(query: String?, model: Message, dateUtils: DateUtils) = binding.apply {
    searchResultProfilePicture.isVisible = true
    searchResultTimestamp.isVisible = true

    searchResultTimestamp.text = dateUtils.getDisplayFormattedTimeSpanString(
        model.messageResult.sentTimestampMs
    )

    searchResultProfilePicture.setThemedContent {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = MessagingModuleConfiguration.shared.avatarUtils.getUIDataFromRecipient(model.messageResult.conversationRecipient)
        )
    }
    val textSpannable = SpannableStringBuilder()
    if (model.messageResult.conversationRecipient != model.messageResult.messageRecipient) {
        // group chat, bind
        val text = "${model.messageResult.messageRecipient.displayName()}: "
        textSpannable.append(text)
    }
    textSpannable.append(getHighlight(
            query,
            model.messageResult.bodySnippet
    ))
    searchResultSubtitle.text = textSpannable
    val title = if (model.isSelf) root.context.getString(R.string.noteToSelf)
        else model.messageResult.conversationRecipient.searchName

    binding.resultTitle.setupTitleWithBadge(
        title =  title,
        showProBadge = model.showProBadge
    )
    searchResultSubtitle.isVisible = true
}

val Recipient.searchName: String
    get() = displayName().takeIf { it.isNotBlank() && !it.looksLikeAccountId }
        ?: address.toString().let(::truncateIdForDisplay)

private val String.looksLikeAccountId: Boolean get() = length > 60 && all { it.isDigit() || it.isLetter() }
