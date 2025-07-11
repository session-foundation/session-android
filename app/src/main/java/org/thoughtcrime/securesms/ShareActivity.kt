/*
 * Copyright (C) 2014-2017 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Parcel
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromExternal
import org.session.libsession.utilities.DistributionTypes
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.ViewUtil
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.components.SearchToolbar
import org.thoughtcrime.securesms.components.SearchToolbar.SearchListener
import org.thoughtcrime.securesms.contacts.ShareContactListFragment
import org.thoughtcrime.securesms.contacts.ShareContactListFragment.OnContactSelectedListener
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.dependencies.DatabaseComponent.Companion.get
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import java.io.FileInputStream
import java.io.IOException

// An activity to quickly share content with contacts.
@AndroidEntryPoint
class ShareActivity : ScreenLockActionBarActivity(), OnContactSelectedListener {

    private val TAG = ShareActivity::class.java.simpleName

    companion object {
        const val EXTRA_THREAD_ID          = "thread_id"
        const val EXTRA_ADDRESS_MARSHALLED = "address_marshalled"
        const val EXTRA_DISTRIBUTION_TYPE  = "distribution_type"
    }

     override val applyDefaultWindowInsets: Boolean
         get() = false

    // Lateinit UI elements
    private lateinit var contactsFragment: ShareContactListFragment
    private lateinit var searchToolbar: SearchToolbar
    private lateinit var searchAction: ImageView
    private lateinit var progressWheel: View

    private var resolvedExtra: Uri? = null
    private var resolvedPlaintext: CharSequence? = null
    private var mimeType: String? = null
    private var isPassingAlongMedia = false
    private var resolveTask: ResolveMediaTask? = null

    override fun onCreate(icicle: Bundle?, ready: Boolean) {

        intent.putExtra(ShareContactListFragment.REFRESHABLE, false)

        setContentView(R.layout.share_activity)

        initializeToolbar()
        initializeResources()
        initializeSearch()
        initializeMedia()


        // only apply inset padding at the top, so the child fragment can allow its recyclerview all the way down
        findViewById<View>(android.R.id.content).applySafeInsetsPaddings(
            consumeInsets = false,
            applyBottom = false,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initializeMedia()
    }

    public override fun onPause() {
        super.onPause()
        if (!isPassingAlongMedia && resolvedExtra != null) {
            BlobUtils.getInstance().delete(this, resolvedExtra!!)
            if (!isFinishing) { finish() }
        }
    }

    override fun onBackPressed() {
        if (searchToolbar.isVisible()) searchToolbar.collapse()
        else super.onBackPressed();
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun initializeToolbar() {
        val toolbarTitle = findViewById<TextView>(R.id.title)
        toolbarTitle.text = Phrase.from(applicationContext, R.string.shareToSession)
            .put(APP_NAME_KEY, getString(R.string.app_name))
            .format().toString()
    }

    private fun initializeResources() {
        progressWheel = findViewById<View>(R.id.progress_wheel)
        searchToolbar = findViewById<SearchToolbar>(R.id.search_toolbar)
        searchAction = findViewById<ImageView>(R.id.search_action)
        contactsFragment = supportFragmentManager.findFragmentById(R.id.contact_selection_list_fragment) as ShareContactListFragment
        contactsFragment.onContactSelectedListener = this
    }

    private fun initializeSearch() {
        searchAction.setOnClickListener(View.OnClickListener { v: View? ->
            searchToolbar.display(
                searchAction.x + (searchAction.width  / 2),
                searchAction.y + (searchAction.height / 2)
            )
        })

        searchToolbar.setListener(object : SearchListener {
            override fun onSearchTextChange(text: String?) { contactsFragment.setQueryFilter(text) }
            override fun onSearchClosed() { contactsFragment.resetQueryFilter() }
        })
    }

    private fun initializeMedia() {
        val context: Context = this
        isPassingAlongMedia = false

        val streamExtra = intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM)
        var charSequenceExtra: CharSequence? = null
        try {
            charSequenceExtra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }
        catch (e: Exception) {
            // It's not necessarily an issue if there's no text extra when sharing files - but we do
            // have to catch any failed attempt.
        }
        mimeType = getMimeType(streamExtra)

        if (streamExtra != null && PartAuthority.isLocalUri(streamExtra)) {
            isPassingAlongMedia = true
            resolvedExtra = streamExtra
            handleResolvedMedia(intent, false)
        } else if (charSequenceExtra != null && mimeType != null && mimeType!!.startsWith("text/")) {
            resolvedPlaintext = charSequenceExtra
            handleResolvedMedia(intent, false)
        } else {
            contactsFragment?.view?.visibility = View.GONE
            progressWheel.visibility = View.VISIBLE
            resolveTask = ResolveMediaTask(context)
            resolveTask!!.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, streamExtra)
        }
    }

    private fun handleResolvedMedia(intent: Intent, animate: Boolean) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        val distributionType = intent.getIntExtra(EXTRA_DISTRIBUTION_TYPE, -1)
        var address: Address? = null

        if (intent.hasExtra(EXTRA_ADDRESS_MARSHALLED)) {
            val parcel = Parcel.obtain()
            val marshalled = intent.getByteArrayExtra(EXTRA_ADDRESS_MARSHALLED)
            parcel.unmarshall(marshalled!!, 0, marshalled.size)
            parcel.setDataPosition(0)
            address = parcel.readParcelable<Address?>(classLoader)
            parcel.recycle()
        }

        val hasResolvedDestination = threadId != -1L && address != null && distributionType != -1

        if (!hasResolvedDestination && animate) {
            ViewUtil.fadeIn(contactsFragment.requireView(), 300)
            ViewUtil.fadeOut(progressWheel, 300)
        } else if (!hasResolvedDestination) {
            contactsFragment.requireView().visibility = View.VISIBLE
            progressWheel.visibility = View.GONE
        } else {
            createConversation(threadId, address, distributionType)
        }
    }

    private fun createConversation(threadId: Long, address: Address?, distributionType: Int) {
        val intent = getBaseShareIntent(ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.ADDRESS, address)
        intent.putExtra(ConversationActivityV2.THREAD_ID, threadId)

        isPassingAlongMedia = true
        startActivity(intent)
    }

    private fun getBaseShareIntent(target: Class<*>): Intent {
        val intent = Intent(this, target)

        if (resolvedExtra != null) {
            intent.setDataAndType(resolvedExtra, mimeType)
        } else if (resolvedPlaintext != null) {
            intent.putExtra(Intent.EXTRA_TEXT, resolvedPlaintext)
            intent.setType("text/plain")
        }

        return intent
    }

    private fun getMimeType(uri: Uri?): String? {
        if (uri != null) {
            val mimeType = MediaUtil.getMimeType(applicationContext, uri)
            if (mimeType != null) return mimeType
        }
        return MediaUtil.getJpegCorrectedMimeTypeIfRequired(intent.type)
    }

    override fun onContactSelected(number: String?) {
        val recipient = Recipient.from(this, fromExternal(this, number), true)
        val existingThread = get(this).threadDatabase().getThreadIdIfExistsFor(recipient)
        createConversation(existingThread, recipient.address, DistributionTypes.DEFAULT)
    }

    override fun onContactDeselected(number: String?) { /* Nothing */ }

    override fun onDestroy() {
        super.onDestroy()
        if (resolveTask != null) resolveTask!!.cancel(true)
    }

    @SuppressLint("StaticFieldLeak")
    private inner class ResolveMediaTask(private val context: Context) : AsyncTask<Uri?, Void?, Uri?>() {

        override fun doInBackground(vararg uris: Uri?): Uri? {
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

                val cursor = contentResolver.query(uris[0]!!, arrayOf<String>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
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

        override fun onPostExecute(uri: Uri?) {
            resolvedExtra = uri
            handleResolvedMedia(intent, true)
        }
    }
}