package org.thoughtcrime.securesms.linkpreview

import android.content.Context
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.utilities.Debouncer
import org.session.libsession.utilities.Util
import org.thoughtcrime.securesms.net.RequestController

class LinkPreviewViewModel(
    private val repository: LinkPreviewRepository
) : ViewModel() {

    private val _linkPreviewState = MutableLiveData(LinkPreviewState.empty())
    val linkPreviewState: LiveData<LinkPreviewState> = _linkPreviewState

    private var activeUrl: String? = null
    private var activeRequest: RequestController? = null
    private var userCanceled: Boolean = false
    private val debouncer = Debouncer(250)

    fun onTextChanged(
        context: Context,
        text: String,
    ) {
        debouncer.publish {
            if (TextUtils.isEmpty(text)) {
                userCanceled = false
            }

            if (userCanceled) return@publish

            val links = LinkPreviewUtil.findWhitelistedUrls(text)
            val link: Link? = links.firstOrNull()

            if (link != null && link.url == activeUrl) return@publish

            activeRequest?.cancel()
            activeRequest = null

            if (link == null) {
                activeUrl = null
                _linkPreviewState.postValue(LinkPreviewState.empty())
                return@publish
            }

            _linkPreviewState.postValue(LinkPreviewState.loading())

            activeUrl = link.url
            activeRequest = repository.getLinkPreview(context, link.url) { lp: LinkPreview? ->
                Util.runOnMain {
                    if (!userCanceled) {
                        _linkPreviewState.value = LinkPreviewState.preview(lp)
                    }
                    activeRequest = null
                }
            }
        }
    }

    fun onUserCancel() {
        activeRequest?.cancel()
        activeRequest = null

        userCanceled = true
        activeUrl = null

        debouncer.clear()
        _linkPreviewState.value = LinkPreviewState.empty()
    }

    fun onEnabled() {
        userCanceled = false
    }

    override fun onCleared() {
        activeRequest?.cancel()
        debouncer.clear()
        super.onCleared()
    }

    data class LinkPreviewState(
        val isLoading: Boolean,
        val linkPreview: LinkPreview?
    ) {
        companion object {
            fun loading() = LinkPreviewState(isLoading = true, linkPreview = null)
            fun preview(linkPreview: LinkPreview?) = LinkPreviewState(isLoading = false, linkPreview = linkPreview)
            fun empty() = LinkPreviewState(isLoading = false, linkPreview = null)
        }
    }

    class Factory(
        private val repository: LinkPreviewRepository
    ) : ViewModelProvider.NewInstanceFactory() {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LinkPreviewViewModel(repository) as T
        }
    }
}
