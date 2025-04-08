package org.thoughtcrime.securesms.mediasend

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.annimon.stream.Stream
import dagger.hilt.android.lifecycle.HiltViewModel
import org.session.libsession.utilities.Util.equals
import org.session.libsession.utilities.Util.runOnMain
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.SingleLiveEvent
import java.util.LinkedList
import javax.inject.Inject

/**
 * Manages the observable datasets available in [MediaSendActivity].
 */

@HiltViewModel
internal class MediaSendViewModel @Inject constructor(
    private val application: Application
) : ViewModel() {
    private val selectedMedia: MutableLiveData<List<Media>?>
    private val bucketMedia: MutableLiveData<List<Media>>
    private val position: MutableLiveData<Int>
    private val bucketId: MutableLiveData<String>
    private val folders: MutableLiveData<List<MediaFolder>>
    private val countButtonState: MutableLiveData<CountButtonState>
    private val cameraButtonVisibility: MutableLiveData<Boolean>
    private val error: SingleLiveEvent<Error>
    private val savedDrawState: MutableMap<Uri, Any>

    private val mediaConstraints: MediaConstraints = MediaConstraints.getPushMediaConstraints()
    private val repository: MediaRepository = MediaRepository()

    var body: CharSequence
        private set
    private var countButtonVisibility: CountButtonState.Visibility
    private var sentMedia: Boolean = false
    private var lastImageCapture: Optional<Media>

    init {
        this.selectedMedia = MutableLiveData()
        this.bucketMedia = MutableLiveData()
        this.position = MutableLiveData()
        this.bucketId = MutableLiveData()
        this.folders = MutableLiveData()
        this.countButtonState = MutableLiveData()
        this.cameraButtonVisibility = MutableLiveData()
        this.error = SingleLiveEvent()
        this.savedDrawState = HashMap()
        this.countButtonVisibility = CountButtonState.Visibility.FORCED_OFF
        this.lastImageCapture = Optional.absent()
        this.body = ""

        position.value = -1
        countButtonState.value = CountButtonState(0, countButtonVisibility)
        cameraButtonVisibility.value = false
    }

    fun onSelectedMediaChanged(context: Context, newMedia: List<Media?>) {
        repository.getPopulatedMedia(context, newMedia,
            { populatedMedia: List<Media> ->
                runOnMain(
                    {
                        var filteredMedia: List<Media> =
                            getFilteredMedia(context, populatedMedia, mediaConstraints)
                        if (filteredMedia.size != newMedia.size) {
                            error.setValue(Error.ITEM_TOO_LARGE)
                        } else if (filteredMedia.size > MAX_SELECTED_FILES) {
                            filteredMedia = filteredMedia.subList(0, MAX_SELECTED_FILES)
                            error.setValue(Error.TOO_MANY_ITEMS)
                        }

                        if (filteredMedia.size > 0) {
                            val computedId: String = Stream.of(filteredMedia)
                                .skip(1)
                                .reduce(filteredMedia.get(0).bucketId ?: Media.ALL_MEDIA_BUCKET_ID,
                                    { id: String?, m: Media ->
                                        if (equals(id, m.bucketId ?: Media.ALL_MEDIA_BUCKET_ID)) {
                                            return@reduce id
                                        } else {
                                            return@reduce Media.ALL_MEDIA_BUCKET_ID
                                        }
                                    })
                            bucketId.setValue(computedId)
                        } else {
                            bucketId.setValue(Media.ALL_MEDIA_BUCKET_ID)
                            countButtonVisibility = CountButtonState.Visibility.CONDITIONAL
                        }

                        selectedMedia.setValue(filteredMedia)
                        countButtonState.setValue(
                            CountButtonState(
                                filteredMedia.size,
                                countButtonVisibility
                            )
                        )
                    })
            })
    }

    fun onSingleMediaSelected(context: Context, media: Media) {
        repository.getPopulatedMedia(context, listOf(media),
            { populatedMedia: List<Media> ->
                runOnMain(
                    {
                        val filteredMedia: List<Media> =
                            getFilteredMedia(context, populatedMedia, mediaConstraints)
                        if (filteredMedia.isEmpty()) {
                            error.setValue(Error.ITEM_TOO_LARGE)
                            bucketId.setValue(Media.ALL_MEDIA_BUCKET_ID)
                        } else {
                            bucketId.setValue(filteredMedia.get(0).bucketId ?: Media.ALL_MEDIA_BUCKET_ID)
                        }

                        countButtonVisibility = CountButtonState.Visibility.FORCED_OFF

                        selectedMedia.value = filteredMedia
                        countButtonState.setValue(
                            CountButtonState(
                                filteredMedia.size,
                                countButtonVisibility
                            )
                        )
                    })
            })
    }

    fun onMultiSelectStarted() {
        countButtonVisibility = CountButtonState.Visibility.FORCED_ON
        countButtonState.value =
            CountButtonState(selectedMediaOrDefault.size, countButtonVisibility)
    }

    fun onImageEditorStarted() {
        countButtonVisibility = CountButtonState.Visibility.FORCED_OFF
        countButtonState.value =
            CountButtonState(selectedMediaOrDefault.size, countButtonVisibility)
        cameraButtonVisibility.value = false
    }

    fun onCameraStarted() {
        countButtonVisibility = CountButtonState.Visibility.CONDITIONAL
        countButtonState.value =
            CountButtonState(selectedMediaOrDefault.size, countButtonVisibility)
        cameraButtonVisibility.value = false
    }

    fun onItemPickerStarted() {
        countButtonVisibility = CountButtonState.Visibility.CONDITIONAL
        countButtonState.value =
            CountButtonState(selectedMediaOrDefault.size, countButtonVisibility)
        cameraButtonVisibility.value = true
    }

    fun onFolderPickerStarted() {
        countButtonVisibility = CountButtonState.Visibility.CONDITIONAL
        countButtonState.value =
            CountButtonState(selectedMediaOrDefault.size, countButtonVisibility)
        cameraButtonVisibility.value = true
    }

    fun onBodyChanged(body: CharSequence) {
        this.body = body
    }

    fun onFolderSelected(bucketId: String) {
        this.bucketId.value = bucketId
        bucketMedia.value =
            emptyList()
    }

    fun onPageChanged(position: Int) {
        if (position < 0 || position >= selectedMediaOrDefault.size) {
            Log.w(TAG,
                "Tried to move to an out-of-bounds item. Size: " + selectedMediaOrDefault.size + ", position: " + position
            )
            return
        }

        this.position.value = position
    }

    fun onMediaItemRemoved(context: Context, position: Int) {
        if (position < 0 || position >= selectedMediaOrDefault.size) {
            Log.w(
                TAG,
                "Tried to remove an out-of-bounds item. Size: " + selectedMediaOrDefault.size + ", position: " + position
            )
            return
        }

        val updatedList = selectedMediaOrDefault.toMutableList()
        val removed: Media = updatedList.removeAt(position)

        if (BlobProvider.isAuthority(removed.uri)) {
            BlobProvider.getInstance().delete(context, removed.uri)
        }

        selectedMedia.setValue(updatedList)
    }

    fun onImageCaptured(media: Media) {
        var selected: MutableList<Media>? = selectedMedia.value?.toMutableList()

        if (selected == null) {
            selected = LinkedList()
        }

        if (selected.size >= MAX_SELECTED_FILES) {
            error.setValue(Error.TOO_MANY_ITEMS)
            return
        }

        lastImageCapture = Optional.of(media)

        selected.add(media)
        selectedMedia.setValue(selected)
        position.setValue(selected.size - 1)
        bucketId.setValue(Media.ALL_MEDIA_BUCKET_ID)

        if (selected.size == 1) {
            countButtonVisibility = CountButtonState.Visibility.FORCED_OFF
        } else {
            countButtonVisibility = CountButtonState.Visibility.CONDITIONAL
        }

        countButtonState.setValue(CountButtonState(selected.size, countButtonVisibility))
    }

    fun onImageCaptureUndo(context: Context) {
        val selected: MutableList<Media> = selectedMediaOrDefault.toMutableList()

        if (lastImageCapture.isPresent && selected.contains(lastImageCapture.get()) && selected.size == 1) {
            selected.remove(lastImageCapture.get())
            selectedMedia.value = selected
            countButtonState.value = CountButtonState(selected.size, countButtonVisibility)
            BlobProvider.getInstance().delete(context, lastImageCapture.get().uri)
        }
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
        return selectedMedia
    }

    fun getMediaInBucket(context: Context, bucketId: String): LiveData<List<Media>> {
        repository.getMediaInBucket(context, bucketId,
            { value: List<Media> -> bucketMedia.postValue(value) })
        return bucketMedia
    }

    fun getFolders(context: Context): LiveData<List<MediaFolder>> {
        repository.getFolders(context,
            { value: List<MediaFolder> -> folders.postValue(value) })
        return folders
    }

    fun getCountButtonState(): LiveData<CountButtonState> {
        return countButtonState
    }

    fun getCameraButtonVisibility(): LiveData<Boolean> {
        return cameraButtonVisibility
    }

    fun getPosition(): LiveData<Int> {
        return position
    }

    fun getBucketId(): LiveData<String> {
        return bucketId
    }

    fun getError(): LiveData<Error> {
        return error
    }

    private val selectedMediaOrDefault: List<Media>
        get() = if (selectedMedia.value == null) emptyList() else
            selectedMedia.value!!

    private fun getFilteredMedia(
        context: Context,
        media: List<Media>,
        mediaConstraints: MediaConstraints
    ): List<Media> {
        return Stream.of(media).filter(
            { m: Media ->
                MediaUtil.isGif(m.mimeType) ||
                        MediaUtil.isImageType(m.mimeType) ||
                        MediaUtil.isVideoType(m.mimeType)
            })
            .filter({ m: Media ->
                (MediaUtil.isImageType(m.mimeType) && !MediaUtil.isGif(m.mimeType)) ||
                        (MediaUtil.isGif(m.mimeType) && m.size < mediaConstraints.getGifMaxSize(
                            context
                        )) ||
                        (MediaUtil.isVideoType(m.mimeType) && m.size < mediaConstraints.getVideoMaxSize(
                            context
                        ))
            }).toList()
    }

    override fun onCleared() {
        if (!sentMedia) {
            Stream.of(selectedMediaOrDefault)
                .map({ obj: Media -> obj.uri })
                .filter({ uri: Uri? ->
                    BlobProvider.isAuthority(
                        uri!!
                    )
                })
                .forEach({ uri: Uri? ->
                    BlobProvider.getInstance().delete(
                        application.applicationContext, uri!!
                    )
                })
        }
    }

    internal enum class Error {
        ITEM_TOO_LARGE, TOO_MANY_ITEMS
    }

    internal class CountButtonState(val count: Int, private val visibility: Visibility) {
        val isVisible: Boolean
            get() {
                when (visibility) {
                    Visibility.FORCED_ON -> return true
                    Visibility.FORCED_OFF -> return false
                    Visibility.CONDITIONAL -> return count > 0
                    else -> return false
                }
            }

        internal enum class Visibility {
            CONDITIONAL, FORCED_ON, FORCED_OFF
        }
    }

    companion object {
        private val TAG: String = MediaSendViewModel::class.java.simpleName

        // the maximum amount of files that can be selected to send as attachment
        const val MAX_SELECTED_FILES: Int = 32
    }
}
