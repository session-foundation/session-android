package org.thoughtcrime.securesms.mediasend

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.annimon.stream.Stream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.session.libsession.utilities.Util.equals
import org.session.libsession.utilities.Util.runOnMain
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.util.MediaUtil
import javax.inject.Inject

/**
 * Manages the observable datasets available in [MediaSendActivity].
 */
@HiltViewModel
internal class MediaSendViewModel @Inject constructor(
    private val application: Application,
    proStatusManager: ProStatusManager,
    recipientRepository: RecipientRepository,
    private val context: ApplicationContext,
) : InputbarViewModel(
    application = application,
    proStatusManager = proStatusManager,
    recipientRepository = recipientRepository,
) {
    private val savedDrawState: MutableMap<Uri, Any>

    private val mediaConstraints: MediaConstraints = MediaConstraints.getPushMediaConstraints()
    private val repository: MediaRepository = MediaRepository()

    var body: CharSequence
        private set

    private var sentMedia: Boolean = false
    private var lastImageCapture: Optional<Media>

    private val _uiState = MutableStateFlow(MediaSendUiState())
    val uiState: StateFlow<MediaSendUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<MediaSendEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<MediaSendEffect> = _effects.asSharedFlow()

    // Legacy LiveData bridges (delete later once all UI is Flow/Compose)
    private val selectedMediaLiveData: LiveData<List<Media>?> =
        uiState.map { it.selectedMedia.ifEmpty { null } }.asLiveData()

    private val bucketIdLiveData: LiveData<String> =
        uiState.map { it.bucketId }.asLiveData()

    private val positionLiveData: LiveData<Int> =
        uiState.map { it.position }.asLiveData()

    private val foldersLiveData: LiveData<List<MediaFolder>> =
        uiState.map { it.folders }.asLiveData()

    private val countButtonStateLiveData: LiveData<CountButtonState> =
        uiState.map { CountButtonState(it.count, it.countVisibility) }
            .asLiveData()

    private val cameraButtonVisibilityLiveData: LiveData<Boolean> =
        uiState.map { it.showCameraButton }.asLiveData()

    init {
        this.savedDrawState = HashMap()
        this.lastImageCapture = Optional.absent()
        this.body = ""

        _uiState.value = MediaSendUiState(
            position = -1,
            countVisibility = CountButtonState.Visibility.FORCED_OFF,
            showCameraButton = false
        )
    }

    fun onSelectedMediaChanged(newMedia: List<Media?>) {
        repository.getPopulatedMedia(context, newMedia) { populatedMedia: List<Media> ->
            runOnMain {
                // Use the new filter function that returns valid items AND errors
                var (filteredMedia, errors) = getFilteredMedia(context, populatedMedia, mediaConstraints)

                // Report errors if they occurred
                if (errors.contains(Error.ITEM_TOO_LARGE)) {
                    _effects.tryEmit(MediaSendEffect.ShowError(Error.ITEM_TOO_LARGE))
                } else if (errors.contains(Error.INVALID_TYPE_ONLY)) {
                    _effects.tryEmit(MediaSendEffect.ShowError(Error.INVALID_TYPE_ONLY))
                }else if (errors.contains(Error.MIXED_TYPE)) {
                    _effects.tryEmit(MediaSendEffect.ShowError(Error.MIXED_TYPE))
                }

                if (filteredMedia.size > MAX_SELECTED_FILES) {
                    filteredMedia = filteredMedia.subList(0, MAX_SELECTED_FILES)
                    _effects.tryEmit(MediaSendEffect.ShowError(Error.TOO_MANY_ITEMS))
                }

                val computedId: String =
                    if (filteredMedia.isNotEmpty()) {
                        Stream.of(filteredMedia)
                            .skip(1)
                            .reduce(
                                filteredMedia[0].bucketId ?: Media.ALL_MEDIA_BUCKET_ID
                            ) { id: String?, m: Media ->
                                if (equals(id, m.bucketId ?: Media.ALL_MEDIA_BUCKET_ID)) {
                                    id
                                } else {
                                    Media.ALL_MEDIA_BUCKET_ID
                                }
                            }
                    } else {
                        Media.ALL_MEDIA_BUCKET_ID
                    }

                val newVisibility =
                    if (filteredMedia.isEmpty()) CountButtonState.Visibility.CONDITIONAL
                    else _uiState.value.countVisibility

                _uiState.update {
                    it.copy(
                        selectedMedia = filteredMedia,
                        bucketId = computedId,
                        countVisibility = newVisibility
                    )
                }
            }
        }
    }

    fun onSingleMediaSelected(context: Context, media: Media) {
        repository.getPopulatedMedia(context, listOf(media)) { populatedMedia: List<Media> ->
            runOnMain {
                val (filteredMedia, errors) = getFilteredMedia(context, populatedMedia, mediaConstraints)

                if (filteredMedia.isEmpty()) {
                    if (errors.contains(Error.ITEM_TOO_LARGE)) {
                        _effects.tryEmit(MediaSendEffect.ShowError(Error.ITEM_TOO_LARGE))
                    } else if (errors.contains(Error.INVALID_TYPE_ONLY)) {
                        _effects.tryEmit(MediaSendEffect.ShowError(Error.INVALID_TYPE_ONLY))
                    }else if (errors.contains(Error.MIXED_TYPE)) {
                        _effects.tryEmit(MediaSendEffect.ShowError(Error.MIXED_TYPE))
                    }
                }

                val newBucketId =
                    if (filteredMedia.isEmpty()) Media.ALL_MEDIA_BUCKET_ID
                    else (filteredMedia[0].bucketId ?: Media.ALL_MEDIA_BUCKET_ID)

                _uiState.update {
                    it.copy(
                        selectedMedia = filteredMedia,
                        bucketId = newBucketId,
                        countVisibility = CountButtonState.Visibility.FORCED_OFF
                    )
                }
            }
        }
    }

    fun onMultiSelectStarted() {
        _uiState.update { it.copy(countVisibility = CountButtonState.Visibility.FORCED_ON) }
    }

    fun onImageEditorStarted() {
        _uiState.update {
            it.copy(
                countVisibility = CountButtonState.Visibility.FORCED_OFF,
                showCameraButton = false
            )
        }
    }

    fun onCameraStarted() {
        _uiState.update {
            it.copy(
                countVisibility = CountButtonState.Visibility.CONDITIONAL,
                showCameraButton = false
            )
        }
    }

    fun onItemPickerStarted() {
        _uiState.update {
            it.copy(
                countVisibility = CountButtonState.Visibility.CONDITIONAL,
                showCameraButton = true
            )
        }
    }

    fun onFolderPickerStarted() {
        _uiState.update {
            it.copy(
                countVisibility = CountButtonState.Visibility.CONDITIONAL,
                showCameraButton = true
            )
        }
    }

    fun onBodyChanged(body: CharSequence) {
        this.body = body
    }

    fun onFolderSelected(bucketId: String) {
        _uiState.update { it.copy(bucketId = bucketId, bucketMedia = emptyList()) }
    }

    fun onPageChanged(position: Int) {
        if (position !in selectedMedia.indices) {
            Log.w(TAG,
                "Tried to move to an out-of-bounds item. Size: " + selectedMedia.size + ", position: " + position
            )
            return
        }

        _uiState.update { it.copy(position = position) }
    }

    fun onMediaItemRemoved(position: Int) {
        val current = selectedMedia
        if (position < 0 || position >= current.size) {
            Log.w(
                TAG,
                "Tried to remove an out-of-bounds item. Size: ${current.size}, position: $position"
            )
            return
        }

        val updatedList = current.toMutableList()
        val removed: Media = updatedList.removeAt(position)

        if (BlobUtils.isAuthority(removed.uri)) {
            BlobUtils.getInstance().delete(context, removed.uri)
        }

        _uiState.update { state ->
            val newPos =
                if (updatedList.isEmpty()) -1
                else state.position.coerceIn(0, updatedList.lastIndex)
            state.copy(selectedMedia = updatedList, position = newPos)
        }
    }

    fun onImageCaptured(media: Media) {
        val selected: MutableList<Media> = selectedMedia.toMutableList()

        if (selected.size >= MAX_SELECTED_FILES) {
            _effects.tryEmit(MediaSendEffect.ShowError(Error.TOO_MANY_ITEMS))
            return
        }

        lastImageCapture = Optional.of(media)

        selected.add(media)

        val newVisibility =
            if (selected.size == 1) CountButtonState.Visibility.FORCED_OFF else CountButtonState.Visibility.CONDITIONAL

        _uiState.update {
            it.copy(
                selectedMedia = selected,
                position = selected.size - 1,
                bucketId = Media.ALL_MEDIA_BUCKET_ID,
                countVisibility = newVisibility
            )
        }
    }

    fun onImageCaptureUndo() {
        val last = if (lastImageCapture.isPresent) lastImageCapture.get() else return
        val current = selectedMedia

        if (!(current.size == 1 && current.contains(last))) return

        val updated = current.toMutableList().apply { remove(last) }

        _uiState.update { state ->
            state.copy(
                selectedMedia = updated,
                position = -1
            )
        }

        if (BlobUtils.isAuthority(last.uri)) {
            BlobUtils.getInstance().delete(context, last.uri)
        }

        lastImageCapture = Optional.absent()
    }

    fun saveDrawState(state: Map<Uri, Any>) {
        savedDrawState.clear()
        savedDrawState.putAll(state)
    }

    fun onSendClicked() {
        sentMedia = true
    }

    val drawState: Map<Uri, Any>
        get() = savedDrawState

    fun getSelectedMedia(): LiveData<List<Media>?> {
        return selectedMediaLiveData
    }

    fun getMediaInBucket(bucketId: String): LiveData<List<Media>> {
        // refresh data, but state is stored in uiState
        repository.getMediaInBucket(context, bucketId) { value ->
            _uiState.update { it.copy(bucketMedia = value) }
        }
        return uiState.map { it.bucketMedia }.asLiveData()
    }

    fun getFolders(): LiveData<List<MediaFolder>> {
        repository.getFolders(context) { value ->
            _uiState.update { it.copy(folders = value) }
        }
        return foldersLiveData
    }

    fun getCountButtonState(): LiveData<CountButtonState> {
        return countButtonStateLiveData
    }

    fun getCameraButtonVisibility(): LiveData<Boolean> {
        return cameraButtonVisibilityLiveData
    }

    fun getPosition(): LiveData<Int> {
        return positionLiveData
    }

    fun getBucketId(): LiveData<String> {
        return bucketIdLiveData
    }

    private val selectedMedia: List<Media>
        get() = _uiState.value.selectedMedia


    /**
     * Filters the input list of media.
     * @return A Pair containing:
     * 1. A List of Valid Media items.
     * 2. A Set of Errors encountered during filtering (e.g. ITEM_TOO_LARGE, INVALID_TYPE).
     */
    private fun getFilteredMedia(
        context: Context,
        media: List<Media>,
        mediaConstraints: MediaConstraints
    ): Pair<List<Media>, Set<Error>> {
        val validMedia = ArrayList<Media>()
        val errors = HashSet<Error>()

        // when sharing multiple media, only certain types are valid: images and video
        // currently we can't multi-share other types
        val validMultiMediaCount = media.count {
            MediaUtil.isGif(it.mimeType)
                    || MediaUtil.isImageType(it.mimeType)
                    || MediaUtil.isVideoType(it.mimeType)
        }

        // if there are no valid types at all, return early
        if(validMultiMediaCount == 0){
            errors.add(Error.INVALID_TYPE_ONLY)
            return Pair(validMedia, errors)
        }

        for (m in media) {
            val isGif = MediaUtil.isGif(m.mimeType)
            val isVideo = MediaUtil.isVideoType(m.mimeType)
            val isImage = MediaUtil.isImageType(m.mimeType)

            // Check Type - Not a valid multi share?
            if (!isGif && !isImage && !isVideo) {
                errors.add(Error.MIXED_TYPE)
                continue
            }

            // Check Size constraints
            val isSizeValid = when {
                isGif -> m.size < mediaConstraints.getGifMaxSize(context)
                isVideo -> m.size < mediaConstraints.getVideoMaxSize(context)
                else -> true
            }

            if (!isSizeValid) {
                errors.add(Error.ITEM_TOO_LARGE)
                continue
            }

            validMedia.add(m)
        }

        return Pair(validMedia, errors)
    }

    override fun onCleared() {
        if (!sentMedia) {
            Stream.of(selectedMedia)
                .map { obj: Media -> obj.uri }
                .filter { uri: Uri? ->
                    BlobUtils.isAuthority(
                        uri!!
                    )
                }
                .forEach { uri: Uri? ->
                    BlobUtils.getInstance().delete(context, uri!!)
                }
        }
    }

    internal enum class Error {
        ITEM_TOO_LARGE, TOO_MANY_ITEMS, INVALID_TYPE_ONLY, MIXED_TYPE
    }

    internal class CountButtonState(val count: Int, private val visibility: Visibility) {
        val isVisible: Boolean
            get() {
                return when (visibility) {
                    Visibility.FORCED_ON -> true
                    Visibility.FORCED_OFF -> false
                    Visibility.CONDITIONAL -> count > 0
                }
            }

        internal enum class Visibility {
            CONDITIONAL, FORCED_ON, FORCED_OFF
        }
    }

    data class MediaSendUiState(
        val recipientName: String = "",
        val folders: List<MediaFolder> = emptyList(),
        val bucketId: String = Media.ALL_MEDIA_BUCKET_ID,
        val bucketMedia: List<Media> = emptyList(),
        val selectedMedia: List<Media> = emptyList(),
        val position: Int = -1,
        val countVisibility: CountButtonState.Visibility = CountButtonState.Visibility.FORCED_OFF,
        val showCameraButton: Boolean = false
    ) {
        val count: Int get() = selectedMedia.size
        val showCountButton: Boolean get() =
            when (countVisibility) {
                CountButtonState.Visibility.FORCED_ON -> true
                CountButtonState.Visibility.FORCED_OFF -> false
                CountButtonState.Visibility.CONDITIONAL -> count > 0
            }
    }

    sealed interface MediaSendEffect {
        data class ShowError(val error: Error) : MediaSendEffect
        data class Toast(val messageRes: Int) : MediaSendEffect
        data class ToastText(val message: String) : MediaSendEffect
    }

    companion object {
        private val TAG: String = MediaSendViewModel::class.java.simpleName

        // the maximum amount of files that can be selected to send as attachment
        const val MAX_SELECTED_FILES: Int = 32
    }
}