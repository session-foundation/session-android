package org.thoughtcrime.securesms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.home.search.searchName
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val proStatusManager: ProStatusManager,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val conversationRepository: ConversationRepository,
): ViewModel(){
    private val TAG = ShareViewModel::class.java.simpleName

    private var resolvedExtra: Uri? = null
    private var resolvedPlaintext: CharSequence? = null
    private var mimeType: String? = null
    private var isPassingAlongMedia = false

    // Input: The search query
    private val mutableSearchQuery = MutableStateFlow("")
    // Output: The search query
    val searchQuery: StateFlow<String> get() = mutableSearchQuery

    // Output: the contact items to display and select from
    @OptIn(FlowPreview::class)
    val contacts: StateFlow<List<ConversationItem>> = combine(
        conversationRepository.observeConversationList(),
         mutableSearchQuery.debounce(100L),
         ::filterContacts
    ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hasAnyConversations: StateFlow<Boolean> =
        conversationRepository.observeConversationList()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _uiEvents = MutableSharedFlow<ShareUIEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<ShareUIEvent> get() = _uiEvents

    private val _uiState = MutableStateFlow(UIState(false))
    val uiState: StateFlow<UIState> get() = _uiState



    private fun filterContacts(
        threads: List<ThreadRecord>,
        query: String,
    ): List<ConversationItem> {
        return threads
            .asSequence()
            .filter { thread ->
                val recipient = thread.recipient
                when {
                    // if the recipient is hidden or not approved, ignore it
                    recipient.priority == PRIORITY_HIDDEN || !recipient.approved -> false

                    // If the recipient is blocked, ignore it
                    recipient.blocked -> false

                    // if the recipient is a legacy group, check if deprecation is enabled
                    recipient.address is Address.LegacyGroup -> !deprecationManager.isDeprecated

                    // if the recipient is a community, check if it can write
                    recipient.data is RecipientData.Community && recipient.data.roomInfo?.write != true -> false

                    else -> {
                        val name = if (recipient.isSelf) context.getString(R.string.noteToSelf)
                        else recipient.searchName

                        (query.isBlank() || name.contains(query, ignoreCase = true))
                    }
                }
            }.sortedWith(
                compareBy<ThreadRecord> { !it.recipient.isSelf } // NTS come first
                    .thenByDescending { it.lastMessage?.timestamp } // then order by last message time
            ).map { thread ->
                val recipient = thread.recipient

                ConversationItem(
                    name = if(recipient.isSelf) context.getString(R.string.noteToSelf)
                            else recipient.searchName,
                    address = recipient.address,
                    avatarUIData = avatarUtils.getUIDataFromRecipient(recipient),
                    showProBadge = proStatusManager.shouldShowProBadge(recipient.address)
                )
            }.toList()
    }

    fun onSearchQueryChanged(query: String) {
        mutableSearchQuery.value = query
    }

    fun onPause(): Boolean{
        if (!isPassingAlongMedia && resolvedExtra != null) {
            BlobUtils.getInstance().delete(context, resolvedExtra!!)
            return true
        }

        return false
    }

    fun initialiseMedia(streamExtra: Uri?, charSequenceExtra: CharSequence?, intent: Intent){
        isPassingAlongMedia = false

        mimeType = getMimeType(streamExtra, intent.type)

        if (streamExtra != null && PartAuthority.isLocalUri(streamExtra)) {
            isPassingAlongMedia = true
            resolvedExtra = streamExtra
            handleResolvedMedia(intent)
        } else if (charSequenceExtra != null && mimeType != null && mimeType!!.startsWith("text/")) {
            resolvedPlaintext = charSequenceExtra
            handleResolvedMedia(intent)
        } else {
            _uiState.update { it.copy(showLoader = true) }
            resolveMedia(intent, streamExtra)
        }
    }

    private fun handleResolvedMedia(intent: Intent) {
        val address = IntentCompat.getParcelableExtra(intent, ShareActivity.EXTRA_ADDRESS, Address::class.java)

        if (address is Address.Conversable) {
            createConversation(address)
        } else {
            _uiState.update { it.copy(showLoader = false) }
        }
    }

    private fun resolveMedia(intent: Intent, vararg uris: Uri?){
        viewModelScope.launch(Dispatchers.Default){
            resolvedExtra = getUri(*uris)
            handleResolvedMedia(intent)
        }
    }

    private fun getUri(vararg uris: Uri?): Uri? {
        try {
            if (uris.size != 1 || uris[0] == null) {
                Log.w(TAG, "Invalid URI passed to ResolveMediaTask - bailing.")
                return null
            } else {
                Log.i(TAG, "Resolved URI: " + uris[0]!!.toString() + " - " + uris[0]!!.path)
            }

            var inputStream = if ("file" == uris[0]!!.scheme) {
                FileInputStream(uris[0]!!.path)
            } else {
                context.contentResolver.openInputStream(uris[0]!!)
            }

            if (inputStream == null) {
                Log.w(TAG, "Failed to create input stream during ShareActivity - bailing.")
                return null
            }

            val cursor = context.contentResolver.query(uris[0]!!, arrayOf<String>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            var fileName: String? = null
            var fileSize: Long? = null

            try {
                if (cursor != null && cursor.moveToFirst()) {
                    try {
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, e)
                    }
                }
            } finally {
                cursor?.close()
            }

            return BlobUtils.getInstance()
                .forData(inputStream, if (fileSize == null) 0 else fileSize)
                .withMimeType(mimeType!!)
                .withFileName(fileName!!)
                .createForMultipleSessionsOnDisk(context, BlobUtils.ErrorListener { e: IOException? -> Log.w(TAG, "Failed to write to disk.", e) })
                .get()
        } catch (ioe: Exception) {
            Log.w(TAG, ioe)
            return null
        }
    }

    private fun getMimeType(uri: Uri?, intentType: String?): String? {
        if (uri != null) {
            val mimeType = MediaUtil.getMimeType(context, uri)
            if (mimeType != null) return mimeType
        }
        return MediaUtil.getJpegCorrectedMimeTypeIfRequired(intentType)
    }

    fun onContactItemClicked(address: Address) {
        if (address is Address.Conversable) {
            createConversation(address)
        }
    }


    private fun createConversation(address: Address.Conversable) {
        val intent = ConversationActivityV2.createIntent(
            context = context,
            address = address,
        )

        intent.applyBaseShare()

        isPassingAlongMedia = true
        _uiEvents.tryEmit(ShareUIEvent.GoToScreen(intent))
    }

    private fun Intent.applyBaseShare() {
        if (resolvedExtra != null) {
            setDataAndType(resolvedExtra, mimeType)
        } else if (resolvedPlaintext != null) {
            putExtra(Intent.EXTRA_TEXT, resolvedPlaintext)
            setType("text/plain")
        }
    }

    sealed interface ShareUIEvent {
        data class GoToScreen(val intent: Intent) : ShareUIEvent
    }

    data class UIState(
        val showLoader: Boolean
    )
}

data class ConversationItem(
    val address: Address,
    val name: String,
    val avatarUIData: AvatarUIData,
    val showProBadge: Boolean,
    val lastMessageSent: Long? = null
)