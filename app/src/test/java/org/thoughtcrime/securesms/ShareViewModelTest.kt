package org.thoughtcrime.securesms

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.FileProviderUtil
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest : BaseViewModelTest() {

    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val tokenStore = ShareIntentTokenStore()

    private val recipient: Address.Conversable =
        Address.fromSerialized("0538e63512fd78c04d45b83ec7f0f3d593f60276ce535d1160eb589a00cca7db59")
                as Address.Conversable

    private val viewModel = ShareViewModel(
        context = context,
        avatarUtils = mock(),
        deprecationManager = mock<LegacyGroupDeprecationManager>(),
        shareIntentTokenStore = tokenStore,
        conversationRepository = mock<ConversationRepository> {
            on { observeConversationList() } doReturn flowOf(emptyList())
        },
    )

    // One of our own blob URIs, shaped as BlobUtils mints them: blob/<storage>/<mime>/<name>/<size>/<id>
    private val ownBlobUri: Uri = BlobUtils.CONTENT_URI.buildUpon()
        .appendPath("multi-session-disk")
        .appendPath("text/plain")
        .appendPath("note.txt")
        .appendPath("12")
        .appendPath("11111111-2222-3333-4444-555555555555")
        .build()

    private fun sharedTextIntent() = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "hello")

    private fun sharedUriIntent(uri: Uri) = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)

    // --- What may be opened on the sender's behalf ------------------------------------------------

    @Test
    fun `refuses a file uri, including one naming a file of ours that exists`() {
        val real = File(context.filesDir, "fixture.txt").apply {
            parentFile?.mkdirs()
            writeText("synthetic fixture content")
        }
        assertThat(real.exists()).isTrue() // positive control: the path is readable to this process

        assertThat(viewModel.canReadSharedUri(Uri.fromFile(real))).isFalse()
        assertThat(viewModel.canReadSharedUri(Uri.parse("file:///storage/emulated/0/Download/example.txt"))).isFalse()
    }

    @Test
    fun `refuses schemes that openInputStream would serve without a grant`() {
        assertThat(viewModel.canReadSharedUri(Uri.parse("android.resource://com.example.app/raw/1"))).isFalse()
    }

    @Test
    fun `refuses a content uri naming one of our own providers`() {
        val attachment = AttachmentId(rowId = 42, uniqueId = 1700000000000)

        assertThat(viewModel.canReadSharedUri(ownBlobUri)).isFalse()
        assertThat(viewModel.canReadSharedUri(PartAuthority.getAttachmentDataUri(attachment))).isFalse()
        assertThat(viewModel.canReadSharedUri(PartAuthority.getAttachmentThumbnailUri(attachment))).isFalse()
    }

    @Test
    fun `accepts a content uri from another app's provider`() {
        assertThat(viewModel.canReadSharedUri(Uri.parse("content://com.example.documents/document/12"))).isTrue()
        assertThat(viewModel.canReadSharedUri(Uri.parse("content://media/external/images/media/7"))).isTrue()
    }

    // --- Where the share is allowed to go ---------------------------------------------------------

    @Test
    fun `an address supplied by the caller does not choose the conversation`() = runTest {
        val intent = sharedTextIntent().putExtra(ShareActivity.EXTRA_SHARE_TOKEN, "forged")
            .putExtra("address", recipient as Address)

        viewModel.uiEvents.test {
            viewModel.initialiseMedia(intent)

            expectNoEvents()
        }
        assertThat(viewModel.uiState.value.showLoader).isFalse()
    }

    @Test
    fun `a minted token chooses the conversation it was minted for`() = runTest {
        val intent = sharedTextIntent()
            .putExtra(ShareActivity.EXTRA_SHARE_TOKEN, tokenStore.mint(recipient))

        viewModel.uiEvents.test {
            viewModel.initialiseMedia(intent)

            val event = awaitItem() as ShareViewModel.ShareUIEvent.GoToScreen
            assertThat(event.intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("hello")
        }
    }

    // --- Passing one of our own attachments along -------------------------------------------------

    @Test
    fun `one of our own uris is not passed along for an intent we did not build`() = runTest {
        val intent = sharedUriIntent(ownBlobUri).putExtra("address", recipient as Address)

        viewModel.uiEvents.test {
            viewModel.initialiseMedia(intent)

            expectNoEvents()
        }
        // Nothing was staged, so there is nothing for onPause to tidy up either.
        assertThat(viewModel.onPause()).isFalse()
    }

    // The chooser merges a direct-share target's extras into the sender's own Intent, so a token we
    // minted arrives attached to URIs the sender chose. The token carries a destination here, so the
    // pass-through gate is the only thing that can stop it - without it this reaches the conversation.
    @Test
    fun `a token does not authorise uris it was not minted for`() = runTest {
        val intent = sharedUriIntent(ownBlobUri)
            .putExtra(ShareActivity.EXTRA_SHARE_TOKEN, tokenStore.mint(recipient))

        viewModel.uiEvents.test {
            viewModel.initialiseMedia(intent)

            expectNoEvents()
        }
        assertThat(viewModel.onPause()).isFalse()
    }

    @Test
    fun `one of our own uris is passed along when the token was minted for it`() = runTest {
        val intent = sharedUriIntent(ownBlobUri).putExtra(
            ShareActivity.EXTRA_SHARE_TOKEN,
            tokenStore.mint(recipient, authorisedUris = setOf(ownBlobUri))
        )

        viewModel.uiEvents.test {
            viewModel.initialiseMedia(intent)

            val event = awaitItem() as ShareViewModel.ShareUIEvent.GoToScreen
            assertThat(event.intent.data).isEqualTo(ownBlobUri)
        }
    }

    @Test
    fun `refuses a content uri naming our own FileProvider`() {
        val ours = Uri.parse("content://${FileProviderUtil.AUTHORITY}/internal_cache/example.txt")

        assertThat(viewModel.canReadSharedUri(ours)).isFalse()
    }
}
