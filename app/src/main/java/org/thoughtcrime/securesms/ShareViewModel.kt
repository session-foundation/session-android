package org.thoughtcrime.securesms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ShareActivity.Companion.EXTRA_ADDRESS_MARSHALLED
import org.thoughtcrime.securesms.ShareActivity.Companion.EXTRA_DISTRIBUTION_TYPE
import org.thoughtcrime.securesms.ShareActivity.Companion.EXTRA_THREAD_ID
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.home.search.getSearchName
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    configFactory: ConfigFactory,
    @ApplicationContext private val context: Context,
    private val storage: StorageProtocol,
    private val threadDatabase: ThreadDatabase,
    private val avatarUtils: AvatarUtils,
    private val proStatusManager: ProStatusManager,
    private val deprecationManager: LegacyGroupDeprecationManager,
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
        getConversations(),
         mutableSearchQuery.debounce(100L),
         ::filterContacts
    ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hasAnyConversations: StateFlow<Boolean> =
        getConversations()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _uiEvents = MutableSharedFlow<ShareUIEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<ShareUIEvent> get() = _uiEvents

    private val _uiState = MutableStateFlow(UIState(false))
    val uiState: StateFlow<UIState> get() = _uiState


    @OptIn(ExperimentalCoroutinesApi::class)
    private fun getConversations():Flow<Set<Pair<Recipient, Long>>> = flow {
        val cursor = threadDatabase.conversationList
        val result = mutableSetOf<Pair<Recipient, Long>>()
        threadDatabase.readerFor(cursor).use { reader ->
            while (reader.next != null) {
                val thread = reader.current
                result.add(Pair(thread.recipient, thread.lastMessage?.timestamp ?: 0))
            }
        }

        emit(result)
    }

    private suspend fun filterContacts(
        contacts: Collection<Pair<Recipient, Long>>,
        query: String,
    ): List<ConversationItem> {
        return contacts.filter {
            if(it.first.isLegacyGroupRecipient && deprecationManager.isDeprecated) return@filter false // ignore legacy group when deprecated
            if(it.first.isCommunityRecipient) { // ignore communities without write access
                val threadId = storage.getThreadId(it.first) ?: return@filter false
                val openGroup = storage.getOpenGroup(threadId) ?: return@filter false
                if(!openGroup.canWrite) return@filter false
            }
            if(it.first.isBlocked) return@filter false // ignore blocked contacts

            val name = if(it.first.isLocalNumber) context.getString(R.string.noteToSelf)
            else it.first.getSearchName()

            (query.isBlank() || name.contains(query, ignoreCase = true))
        }.sortedWith(
            compareBy<Pair<Recipient, Long>> { !it.first.isLocalNumber } // NTS come first
                .thenByDescending { it.second } // then order by last message time
        ).map {
            ConversationItem(
                name = if(it.first.isLocalNumber) context.getString(R.string.noteToSelf)
                        else it.first.getSearchName(),
                address = it.first.address,
                avatarUIData = avatarUtils.getUIDataFromRecipient(it.first),
                showProBadge = proStatusManager.shouldShowProBadge(it.first.address)
            )
        }
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
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        val distributionType = intent.getIntExtra(EXTRA_DISTRIBUTION_TYPE, -1)
        var address: Address? = null

        if (intent.hasExtra(EXTRA_ADDRESS_MARSHALLED)) {
            val parcel = Parcel.obtain()
            val marshalled = intent.getByteArrayExtra(EXTRA_ADDRESS_MARSHALLED)
            parcel.unmarshall(marshalled!!, 0, marshalled.size)
            parcel.setDataPosition(0)
            address = parcel.readParcelable<Address?>(context.classLoader)
            parcel.recycle()
        }

        val hasResolvedDestination = threadId != -1L && address != null && distributionType != -1

         if (!hasResolvedDestination) {
             _uiState.update { it.copy(showLoader = false) }
        } else {
            createConversation(threadId, address)
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

        viewModelScope.launch(Dispatchers.Default) {
            val recipient = Recipient.from(context, address, true)
            val existingThread = threadDatabase.getThreadIdIfExistsFor(recipient)
            createConversation(existingThread, recipient.address)
        }
    }


    private fun createConversation(threadId: Long, address: Address?) {
        val intent = getBaseShareIntent(ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.ADDRESS, address)
        intent.putExtra(ConversationActivityV2.THREAD_ID, threadId)

        isPassingAlongMedia = true
        _uiEvents.tryEmit(ShareUIEvent.GoToScreen(intent))
    }

    private fun getBaseShareIntent(target: Class<*>): Intent {
        val intent = Intent(context, target)

        if (resolvedExtra != null) {
            intent.setDataAndType(resolvedExtra, mimeType)
        } else if (resolvedPlaintext != null) {
            intent.putExtra(Intent.EXTRA_TEXT, resolvedPlaintext)
            intent.setType("text/plain")
        }

        return intent
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