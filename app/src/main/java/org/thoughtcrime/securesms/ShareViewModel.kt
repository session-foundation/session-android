package org.thoughtcrime.securesms

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
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
import org.thoughtcrime.securesms.util.FileProviderUtil
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val shareIntentTokenStore: ShareIntentTokenStore,
    conversationRepository: ConversationRepository,
): ViewModel(){

    private val TAG = ShareViewModel::class.java.simpleName

    private var resolvedExtras: List<Uri> = emptyList()
    private var resolvedPlaintext: CharSequence? = null
    private var mimeType: String? = null
    private var isPassingAlongMedia = false
    private var minted: ShareIntentTokenStore.Minted? = null
    private var shareDestination: Address? = null

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

        val minted = shareIntentTokenStore.resolve(intent.getStringExtra(ShareActivity.EXTRA_SHARE_TOKEN))
        this.minted = minted
        shareDestination = minted?.address

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

        // A URI naming one of our own providers is passed to the attachment manager verbatim, which
        // reads it as us - so it resolves to the viewer's own message history rather than to anything
        // the sender holds. Only the exact URIs a token was minted for may take that route: holding a
        // token is not enough, because the chooser merges our direct-share extras into the sender's
        // own Intent, so a valid token can arrive alongside URIs we never vouched for.
        if (minted != null && uris.isNotEmpty() && uris.all { minted.authorises(it) }) {
            isPassingAlongMedia = true
            resolvedExtras = uris
            handleResolvedMedia()
        } else if (
            uris.isEmpty() &&
            charSequenceExtra != null &&
            (mimeType?.startsWith("text/") == true)
        ) {
            resolvedPlaintext = charSequenceExtra
            handleResolvedMedia()
        } else if (uris.isNotEmpty()) {
            _uiState.update { it.copy(showLoader = true) }
            resolveMedia(uris)
        } else {
            _uiState.update { it.copy(showLoader = false) }
        }
    }

    private fun handleResolvedMedia() {
        val address = shareDestination
        if (address is Address.Conversable) {
            createConversation(address)
        } else {
            _uiState.update { it.copy(showLoader = false) }
        }
    }

    private fun resolveMedia(uris: List<Uri>){
        viewModelScope.launch(Dispatchers.Default){
            resolvedExtras = uris.mapNotNull { processSingleUri(it) }
            handleResolvedMedia()
        }
    }

    /**
     * Whether a URI offered by whoever sent the share Intent may be opened on their behalf.
     */
    @VisibleForTesting
    internal fun canReadSharedUri(uri: Uri): Boolean {
        // A URI grant is what makes the sender's content readable to us, and only content:// carries
        // one. openInputStream also accepts file:// and android.resource://, both of which it opens
        // as this app with nothing consulted, so anything this app can reach would be readable by
        // whoever sent the Intent.
        if (ContentResolver.SCHEME_CONTENT != uri.scheme) {
            Log.w(TAG, "Refusing a shared URI that carries no content grant.")
            return false
        }

        // Our own providers answer us whether or not they are exported, so these resolve to our own
        // data rather than to anything the sender holds. That covers the attachment and blob
        // providers, and equally our FileProvider, whose configured roots include the cache
        // directory and external storage.
        if (PartAuthority.isLocalUri(uri) || FileProviderUtil.AUTHORITY == uri.authority) {
            Log.w(TAG, "Refusing a shared URI that names one of our own providers.")
            return false
        }

        return true
    }

    private fun processSingleUri(uri: Uri): Uri? {
        try {
            Log.i(TAG, "Resolving URI: " + uri.toString() + " - " + uri.path)

            if (!canReadSharedUri(uri)) return null

            val inputStream = context.contentResolver.openInputStream(uri)

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