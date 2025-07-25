package org.thoughtcrime.securesms.conversation.v2.mention

import android.app.Application
import android.content.ContentResolver
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.text.getSpans
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.allWithStatus
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.database.DatabaseContentProviders.Conversation
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.observeChanges

/**
 * A ViewModel that provides the mention search functionality for a text input.
 *
 * To use this ViewModel, you (a view) will need to:
 * 1. Observe the [autoCompleteState] to get the mention search results.
 * 2. Set the EditText's editable factory to [editableFactory], via [android.widget.EditText.setEditableFactory]
 */
class MentionViewModel(
    application: Application,
    threadID: Long,
    contentResolver: ContentResolver,
    threadDatabase: ThreadDatabase,
    groupDatabase: GroupDatabase,
    mmsDatabase: MmsDatabase,
    contactDatabase: SessionContactDatabase,
    memberDatabase: GroupMemberDatabase,
    storage: Storage,
    configFactory: ConfigFactoryProtocol,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        (contentResolver.observeChanges(Conversation.getUriForThread(threadID)) as Flow<Any?>)
            .debounce(500L)
            .onStart { emit(Unit) }
            .mapLatest {
                val recipient = checkNotNull(threadDatabase.getRecipientForThreadId(threadID)) {
                    "Recipient not found for thread ID: $threadID"
                }

                val memberIDs = when {
                    recipient.isLegacyGroupRecipient -> {
                        groupDatabase.getGroupMemberAddresses(recipient.address.toGroupString(), false)
                            .map { it.toString() }
                    }
                    recipient.isGroupV2Recipient -> {
                        storage.getMembers(recipient.address.toString()).map { it.accountId() }
                    }

                    recipient.isCommunityRecipient -> mmsDatabase.getRecentChatMemberIDs(threadID, 20)
                    recipient.isContactRecipient -> listOf(recipient.address.toString())
                    else -> listOf()
                }

                val openGroup = if (recipient.isCommunityRecipient) {
                    storage.getOpenGroup(threadID)
                } else {
                    null
                }

                val moderatorIDs = if (recipient.isCommunityRecipient) {
                    val groupId = openGroup?.id
                    if (groupId.isNullOrBlank()) {
                        emptySet()
                    } else {
                        memberDatabase.getGroupMembersRoles(groupId, memberIDs)
                            .mapNotNullTo(hashSetOf()) { (memberId, roles) ->
                                memberId.takeIf { roles.any { it.isModerator } }
                            }
                    }
                } else if (recipient.isGroupV2Recipient) {
                    configFactory.withGroupConfigs(AccountId(recipient.address.toString())) {
                        it.groupMembers.allWithStatus()
                            .filter { (member, status) -> member.isAdminOrBeingPromoted(status) }
                            .mapTo(hashSetOf()) { (member, _) -> member.accountId() }
                    }
                } else {
                    emptySet()
                }

                val contactContext = if (recipient.isCommunityRecipient) {
                    Contact.ContactContext.OPEN_GROUP
                } else {
                    Contact.ContactContext.REGULAR
                }

                val myId = if (openGroup != null) {
                    requireNotNull(storage.getUserBlindedAccountId(openGroup.publicKey)).hexString
                } else {
                    requireNotNull(storage.getUserPublicKey())
                }

                (sequenceOf(
                    Member(
                        publicKey = myId,
                        name = application.getString(R.string.you),
                        isModerator = myId in moderatorIDs,
                        isMe = true
                    )
                ) + contactDatabase.getContacts(memberIDs)
                    .asSequence()
                    .filter { it.accountID != myId }
                    .map { contact ->
                        Member(
                            publicKey = contact.accountID,
                            name = contact.displayName(contactContext),
                            isModerator = contact.accountID in moderatorIDs,
                            isMe = false
                        )
                    })
                    .toList()
            }
            .flowOn(dispatcher)
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
                        members.mapNotNullTo(mutableListOf()) { searchAndHighlight(it, query.query) }
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
        val candidate = autoCompleteState.members.find { it.member.publicKey == candidatePublicKey } ?: return

        editable.addMention(
            candidate.member,
            query.mentionSymbolStartAt .. (query.mentionSymbolStartAt + query.query.length + 1)
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
        val isModerator: Boolean,
        val isMe: Boolean,
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
                .then(compareBy { it.member.name })
        }
    }

    sealed interface AutoCompleteState {
        object Idle : AutoCompleteState
        object Loading : AutoCompleteState
        data class Result(val members: List<Candidate>, val query: String) : AutoCompleteState
        object Error : AutoCompleteState
    }

    @dagger.assisted.AssistedFactory
    interface AssistedFactory {
        fun create(threadId: Long): Factory
    }

    class Factory @AssistedInject constructor(
        @Assisted private val threadId: Long,
        private val contentResolver: ContentResolver,
        private val threadDatabase: ThreadDatabase,
        private val groupDatabase: GroupDatabase,
        private val mmsDatabase: MmsDatabase,
        private val contactDatabase: SessionContactDatabase,
        private val storage: Storage,
        private val memberDatabase: GroupMemberDatabase,
        private val configFactory: ConfigFactoryProtocol,
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MentionViewModel(
                threadID = threadId,
                contentResolver = contentResolver,
                threadDatabase = threadDatabase,
                groupDatabase = groupDatabase,
                mmsDatabase = mmsDatabase,
                contactDatabase = contactDatabase,
                memberDatabase = memberDatabase,
                storage = storage,
                configFactory = configFactory,
                application = application,
            ) as T
        }
    }
}