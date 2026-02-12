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
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.home.search.searchName
import org.thoughtcrime.securesms.mms.PartAuthority
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
    @ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val deprecationManager: LegacyGroupDeprecationManager,
    conversationRepository: ConversationRepository,
): ViewModel(){

    private val TAG = ShareViewModel::class.java.simpleName

    private var resolvedExtras: List<Uri> = emptyList()
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

    val hasAnyConversations: StateFlow<Boolean?> =
        conversationRepository.observeConversationList()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
                    showProBadge = recipient.shouldShowProBadge
                )
            }.toList()
    }

    fun onSearchQueryChanged(query: String) {
        mutableSearchQuery.value = query
    }

    fun onPause(): Boolean{
        if (!isPassingAlongMedia && resolvedExtras.isNotEmpty()) {
            resolvedExtras.forEach { uri ->
                BlobUtils.getInstance().delete(context, uri)
            }
            return true
        }
        return false
    }

    fun initialiseMedia(intent: Intent){
        // Reset previous state
        resolvedExtras = emptyList()
        resolvedPlaintext = null
        mimeType = null
        isPassingAlongMedia = false

        val action = intent.action
        val type = intent.type
        val incomingUris = ArrayList<Uri>()

        val clipUris = intent.clipData?.let { cd ->
            (0 until cd.itemCount).mapNotNull { cd.getItemAt(it).uri }
        }.orEmpty()

        if (clipUris.isNotEmpty()) {
            incomingUris.addAll(clipUris)
        } else {
            if (Intent.ACTION_SEND == action) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(incomingUris::add)
            } else if (Intent.ACTION_SEND_MULTIPLE == action) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(incomingUris::addAll)
            }
            intent.data?.let(incomingUris::add)
        }

        val uris = incomingUris.distinct()

        var charSequenceExtra: CharSequence? = null
        try {
            charSequenceExtra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }
        catch (e: Exception) {
            // Ignore
        }

        isPassingAlongMedia = false
        mimeType = getMimeType(uris.firstOrNull(), type)

        if (uris.isNotEmpty() && uris.all { PartAuthority.isLocalUri(it) }) {
            isPassingAlongMedia = true
            resolvedExtras = uris
            handleResolvedMedia(intent)
        } else if (
            uris.isEmpty() &&
            charSequenceExtra != null &&
            (mimeType?.startsWith("text/") == true)
        ) {
            resolvedPlaintext = charSequenceExtra
            handleResolvedMedia(intent)
        } else if (uris.isNotEmpty()) {
            _uiState.update { it.copy(showLoader = true) }
            resolveMedia(intent, uris)
        } else {
            _uiState.update { it.copy(showLoader = false) }
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

    private fun resolveMedia(intent: Intent, uris: List<Uri>){
        viewModelScope.launch(Dispatchers.Default){
            resolvedExtras = uris.mapNotNull { processSingleUri(it) }
            handleResolvedMedia(intent)
        }
    }

    private fun processSingleUri(uri: Uri): Uri? {
        try {
            Log.i(TAG, "Resolving URI: " + uri.toString() + " - " + uri.path)

            val inputStream = if ("file" == uri.scheme) {
                FileInputStream(uri.path)
            } else {
                context.contentResolver.openInputStream(uri)
            }

            if (inputStream == null) {
                Log.w(TAG, "Failed to create input stream during ShareActivity - bailing.")
                return null
            }

            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
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

            val specificMime = MediaUtil.getMimeType(context, uri) ?: mimeType ?: "application/octet-stream"

            return BlobUtils.getInstance()
                .forData(inputStream, if (fileSize == null) 0 else fileSize)
                .withMimeType(specificMime)
                .withFileName(fileName ?: "unknown")
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
        if (resolvedExtras.isNotEmpty()) {
            if (resolvedExtras.size == 1) {
                action = Intent.ACTION_SEND
                setDataAndType(resolvedExtras.first(), mimeType)
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                type = mimeType ?: "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(resolvedExtras))
            }
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