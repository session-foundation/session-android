package org.thoughtcrime.securesms.conversation.v2.mention

import android.app.Application
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.text.getSpans
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.isCommunity
import org.session.libsession.utilities.isGroupV2
import org.session.libsession.utilities.isLegacyGroup
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.toGroupString
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils

/**
 * A ViewModel that provides the mention search functionality for a text input.
 *
 * To use this ViewModel, you (a view) will need to:
 * 1. Observe the [autoCompleteState] to get the mention search results.
 * 2. Set the EditText's editable factory to [editableFactory], via [android.widget.EditText.setEditableFactory]
 */
@HiltViewModel(assistedFactory = MentionViewModel.Factory::class)
class MentionViewModel @AssistedInject constructor(
    application: Application,
    @Assisted address: Address,
    threadDatabase: ThreadDatabase,
    groupDatabase: GroupDatabase,
    storage: Storage,
    recipientRepository: RecipientRepository,
    avatarUtils: AvatarUtils,
    mmsSmsDatabase: MmsSmsDatabase
) : ViewModel() {
    private val editable = MentionEditable()

    /**
     * A factory that creates a new [Editable] instance that is backed by the same source of truth
     * used by this viewModel.
     */
    val editableFactory = object : Editable.Factory() {
        override fun newEditable(source: CharSequence?): Editable {
            if (source === editable) {
                return source
            }

            if (source != null) {
                editable.replace(0, editable.length, source)
            }

            return editable
        }
    }

    @Suppress("OPT_IN_USAGE")
    private val members: StateFlow<List<Member>?> =
        recipientRepository.observeRecipient(address)
            .mapLatest { recipient ->
                val threadID = withContext(Dispatchers.Default) {
                    threadDatabase.getThreadIdIfExistsFor(address)
                }

                val memberIDs = when {
                    address.isLegacyGroup -> {
                        groupDatabase.getGroupMemberAddresses(address.toGroupString(), false)
                            .map { it.toString() }
                    }
                    address.isGroupV2 -> {
                        storage.getMembers(address.toString()).map { it.accountId() }
                    }

                    address.isCommunity -> mmsSmsDatabase.getRecentChatMemberAddresses(
                        threadID,
                        300
                    )
                    else -> listOf(address.address)
                }

                val openGroup = (recipient.data as? RecipientData.Community)?.openGroup

                val myId = if (openGroup != null) {
                    requireNotNull(storage.getUserBlindedAccountId(openGroup.publicKey)).hexString
                } else {
                    requireNotNull(storage.getUserPublicKey())
                }

                (sequenceOf(
                    Member(
                        publicKey = myId,
                        name = application.getString(R.string.you),
                        showAdminCrown = (recipient.data as? RecipientData.GroupLike)?.shouldShowAdminCrown(
                            AccountId(myId)
                        ) == true,
                        isMe = true,
                        avatarData = avatarUtils.getUIDataFromRecipient(recipientRepository.getSelf()),
                    )
                ) + memberIDs
                    .asSequence()
                    .filter { it != myId }
                    .map { recipientRepository.getRecipientSync(Address.fromSerialized(it)) }
                    .mapNotNull { m ->
                        val accountId = (m.address as? Address.WithAccountId)?.accountId ?: return@mapNotNull null
                        Member(
                            publicKey = accountId.hexString,
                            name = m.displayName(attachesBlindedId = true),
                            showAdminCrown = (recipient.data as? RecipientData.GroupLike)?.shouldShowAdminCrown(accountId) == true,
                            isMe = false,
                            avatarData = avatarUtils.getUIDataFromRecipient(m)
                        )
                    })
                    .toList()
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(10_000L), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val autoCompleteState: StateFlow<AutoCompleteState> = editable
        .observeMentionSearchQuery()
        .flatMapLatest { query ->
            if (query == null) {
                return@flatMapLatest flowOf(AutoCompleteState.Idle)
            }

            members.mapLatest { members ->
                if (members == null) {
                    return@mapLatest AutoCompleteState.Loading
                }

                withContext(Dispatchers.Default) {
                    val filtered = if (query.query.isBlank()) {
                        members.mapTo(mutableListOf()) { Candidate(it, it.name, 0) }
                    } else {
                        members.mapNotNullTo(mutableListOf()) {
                            searchAndHighlight(
                                it,
                                query.query
                            )
                        }
                    }

                    filtered.sortWith(Candidate.MENTION_LIST_COMPARATOR)
                    AutoCompleteState.Result(filtered, query.query)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), AutoCompleteState.Idle)

    private fun searchAndHighlight(
        haystack: Member,
        needle: String
    ): Candidate? {
        val startIndex = haystack.name.indexOf(needle, ignoreCase = true)

        return if (startIndex >= 0) {
            val endIndex = startIndex + needle.length
            val spanned = SpannableStringBuilder(haystack.name)
            spanned.setSpan(
                StyleSpan(Typeface.BOLD),
                startIndex,
                endIndex,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
            Candidate(member = haystack, nameHighlighted = spanned, matchScore = startIndex)
        } else {
            null
        }
    }

    fun onCandidateSelected(candidatePublicKey: String) {
        val query = editable.mentionSearchQuery ?: return
        val autoCompleteState = autoCompleteState.value as? AutoCompleteState.Result ?: return
        val candidate =
            autoCompleteState.members.find { it.member.publicKey == candidatePublicKey } ?: return

        editable.addMention(
            candidate.member,
            query.mentionSymbolStartAt..(query.mentionSymbolStartAt + query.query.length + 1)
        )
    }

    /**
     * Given a message body, normalize it by replacing the display name following '@' with their public key.
     *
     * As "@123456" is the standard format for mentioning a user, this method will replace "@Alice" with "@123456"
     */
    fun normalizeMessageBody(): String {
        return deconstructMessageMentions().trim()
    }

    fun deconstructMessageMentions(): String {
        val spansWithRanges = editable.getSpans<MentionSpan>()
            .mapTo(mutableListOf()) { span ->
                span to (editable.getSpanStart(span)..editable.getSpanEnd(span))
            }

        spansWithRanges.sortBy { it.second.first }

        val sb = StringBuilder()
        var offset = 0
        for ((span, range) in spansWithRanges) {
            // Add content before the mention span
            val thisMentionStart = range.first
            val lastMentionEnd = offset.coerceAtMost(thisMentionStart)
            sb.append(editable, lastMentionEnd, thisMentionStart)

            // Replace the mention span with "@public key"
            sb.append('@').append(span.member.publicKey)

            // Check if the original mention span ended with a space
            // The span includes the space, so we need to preserve it in the deconstructed version
            if (range.last < editable.length && editable[range.last] == ' ') {
                sb.append(' ')
            }

            // Move offset to after the mention span (including the space)
            offset = (range.last + 1).coerceAtMost(editable.length)
        }

        // Add the remaining content
        sb.append(editable, offset, editable.length)
        return sb.toString()
    }

    suspend fun reconstructMentions(raw: String): Editable {
        editable.replace(0, editable.length, raw)

        val memberList = members.filterNotNull().first()

        MentionUtilities.substituteIdsInPlace(
            editable,
            memberList.associateBy { it.publicKey }
        )

        return editable
    }

    data class Member(
        val publicKey: String,
        val name: String,
        val showAdminCrown: Boolean,
        val isMe: Boolean,
        val avatarData: AvatarUIData,
    )

    data class Candidate(
        val member: Member,
        // The name with the matching keyword highlighted.
        val nameHighlighted: CharSequence,
        // The score of matching the query keyword. Lower is better.
        val matchScore: Int,
    ) {
        companion object {
            val MENTION_LIST_COMPARATOR = compareBy<Candidate> { !it.member.isMe }
                .thenBy { it.matchScore }
        }
    }

    sealed interface AutoCompleteState {
        object Idle : AutoCompleteState
        object Loading : AutoCompleteState
        data class Result(val members: List<Candidate>, val query: String) : AutoCompleteState
        object Error : AutoCompleteState
    }

    @AssistedFactory
    interface Factory {
        fun create(address: Address): MentionViewModel
    }
}