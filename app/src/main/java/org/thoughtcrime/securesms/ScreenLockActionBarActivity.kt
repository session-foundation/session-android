package org.thoughtcrime.securesms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.util.Locale
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.onboarding.landing.LandingActivity
import org.thoughtcrime.securesms.service.KeyCachingService

abstract class ScreenLockActionBarActivity : BaseActionBarActivity() {
    private val TAG = ScreenLockActionBarActivity::class.java.simpleName

    companion object {
        const val LOCALE_EXTRA: String = "locale_extra"

        private const val STATE_NORMAL            = 0
        private const val STATE_SCREEN_LOCKED     = 1
        private const val STATE_UPGRADE_DATABASE  = 2
        private const val STATE_WELCOME_SCREEN    = 3

        private fun getStateName(state: Int): String {
            return when (state) {
                STATE_NORMAL           -> "STATE_NORMAL"
                STATE_SCREEN_LOCKED    -> "STATE_SCREEN_LOCKED"
                STATE_UPGRADE_DATABASE -> "STATE_UPGRADE_DATABASE"
                STATE_WELCOME_SCREEN   -> "STATE_WELCOME_SCREEN"
                else                   -> "UNKNOWN_STATE"
            }
        }

        // If we're sharing files we need to cache the data from the share Intent to maintain control of it
        private val cachedIntentFiles = mutableListOf<File>()

        // Called from ConversationActivity.onDestroy() to clean up any cached files that might exist
        fun cleanupCachedFiles() {
            var filesDeletedSuccessfully = true
            for (file in cachedIntentFiles) {
                if (file.exists()) {
                    val success = file.delete()
                    if (!success) { filesDeletedSuccessfully = false }
                }
            }
            if (!filesDeletedSuccessfully) { Log.w(TAG, "Failed to delete one or more cached shared file(s).") }
            cachedIntentFiles.clear()
        }
    }

    private var clearKeyReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Hit ScreenLockActionBarActivity.onCreate(" + savedInstanceState + ")")
        super.onCreate(savedInstanceState)

        val locked = KeyCachingService.isLocked(this) && isScreenLockEnabled(this) && getLocalNumber(this) != null
        routeApplicationState(locked)

        if (!isFinishing) {
            initializeClearKeyReceiver()
            Log.w(TAG, "We aren't finishing so calling onCreate(savedInstanceState, true)")
            onCreate(savedInstanceState, true)
        }
    }

    protected open fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {}

    override fun onPause() {
        Log.i(TAG, "onPause()")
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "ScreenLockActionBarActivity.onDestroy()")
        super.onDestroy()
        removeClearKeyReceiver(this)
    }

    fun onMasterSecretCleared() {
        Log.i(TAG, "onMasterSecretCleared()")
        if (ApplicationContext.getInstance(this).isAppVisible()) routeApplicationState(true)
        else finish()
    }

    protected fun <T : Fragment?> initFragment(@IdRes target: Int, fragment: T): T? {
        return initFragment<T?>(target, fragment, null)
    }

    protected fun <T : Fragment?> initFragment(@IdRes target: Int, fragment: T, locale: Locale?): T? {
        return initFragment<T?>(target, fragment, locale, null)
    }

    protected fun <T : Fragment?> initFragment(@IdRes target: Int, fragment: T, locale: Locale?, extras: Bundle?): T? {
        val args = Bundle()
        args.putSerializable(LOCALE_EXTRA, locale)

        if (extras != null) { args.putAll(extras) }

        fragment!!.setArguments(args)
        supportFragmentManager.beginTransaction()
            .replace(target, fragment)
            .commitAllowingStateLoss()
        return fragment
    }

    private fun routeApplicationState(locked: Boolean) {
        val intent = getIntentForState(getApplicationState(locked))
        if (intent != null) {
            startActivity(intent)
            finish()
        }
    }

    private fun getIntentForState(state: Int): Intent? {
        Log.i(TAG, "routeApplicationState() -  ${getStateName(state)}")

        return when (state) {
            STATE_SCREEN_LOCKED -> getPromptPassphraseIntent()
            STATE_UPGRADE_DATABASE  -> getUpgradeDatabaseIntent()
            STATE_WELCOME_SCREEN    -> getWelcomeIntent()
            else -> null
        }
    }

    private fun getApplicationState(locked: Boolean): Int {
        return if (getLocalNumber(this) == null) {
            STATE_WELCOME_SCREEN
        } else if (locked) {
            STATE_SCREEN_LOCKED
        } else if (DatabaseUpgradeActivity.isUpdate(this)) {
            STATE_UPGRADE_DATABASE
        } else {
            STATE_NORMAL
        }
    }

    private fun getPromptPassphraseIntent(): Intent {
        // If this is an attempt to externally share something while the app is locked then we need
        // to rewrite the intent to reference a cached copy of the shared file.
        // Note: We CANNOT just add `Intent.FLAG_GRANT_READ_URI_PERMISSION` to this intent as we
        // pass it around because we don't have permission to do that (i.e., it doesn't work).
        if (intent.action === "android.intent.action.SEND") {
            val rewrittenIntent = rewriteShareIntentUris(intent)
            return getRoutedIntent(PassphrasePromptActivity::class.java, rewrittenIntent)
        } else {
            return getRoutedIntent(PassphrasePromptActivity::class.java, intent)
        }
    }

    // Unused at present - but useful for debugging!
    private fun printIntentExtras(i: Intent, prefix: String = "") {
        val bundle = i.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                Log.w(TAG, "${prefix}: Key: " + key + " --> Value: " + bundle.get(key))
            }
        }
    }

    // Rewrite the original share Intent, copying any URIs it contains to our app's private cache,
    // and return a new "rewritten" Intent that references the local copies of URIs via our FileProvider.
    // We do this to prevent a SecurityException being thrown regarding ephemeral permissions to
    // view the shared URI which may be available to THIS PassphrasePromptActivity, but which is NOT
    // then valid on the actual ShareActivity which we transfer the Intent through to. With a
    // rewritten copy of the original Intent that references our own cached copy of the URI we have
    // full control over it.
    // Note: We delete any cached file(s) in ConversationActivity.onDestroy.
    private fun rewriteShareIntentUris(originalIntent: Intent): Intent? {
        val rewrittenIntent = Intent(originalIntent)

        // Clear original clipData
        rewrittenIntent.clipData = null

        // Take the first extra key which relates to a file stream
        val extraKey: String? = rewrittenIntent.extras
            ?.keySet()
            ?.firstOrNull { it == "android.intent.extra.STREAM" }

        // If we couldn't find one then we have nothing to re-write and we'll just return the original intent
        if (extraKey == null) {
            Log.i(TAG, "No stream to rewrite - returning original intent")
            return originalIntent
        }

        // Get the path of the file we're sharing
        val streamUriPath = rewrittenIntent.extras?.getString(extraKey)

        // If we're sharing a local file in the downloads folder we don't need to do anything special - we can just use the original intent
        if (streamUriPath?.startsWith("content://com.android.providers.downloads") == true) {
            return originalIntent
        }

        // Grab and rewrite the original intent's clipData - adding it to our rewrittenIntent as we go
        val originalClipData = originalIntent.clipData
        originalClipData?.let { clipData ->
            var newClipData: ClipData? = null
            for (i in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(i)
                val originalUri = item.uri

                if (originalUri != null) {
                    // First, copy the file locally..
                    val localUri = copyFileToCache(originalUri)
                    Log.i(TAG, "rewriteShareIntentUris: localUri is: " + localUri)

                    if (localUri != null) {
                        // ..then create a ClipData from the localUri, not the originalUri!
                        if (newClipData == null) {
                            newClipData = ClipData.newUri(contentResolver, "Shared Content", localUri)

                            // CAREFUL: Do NOT put the localUri.path in the extra - put the localUri itself!
                            rewrittenIntent.putExtra(extraKey, localUri)
                        } else {
                            // If we already have some clipData we can add to it rather than recreating it
                            newClipData.addItem(ClipData.Item(localUri))
                        }
                    } else {
                        // Moan if copying the originalUri failed - not much we can do in this case but let the calling function handle things
                        Log.e(TAG, "Could not rewrite URI: $originalUri")
                        return null
                    }
                }
            }

            if (newClipData != null) {
                Log.i(TAG, "Adding newClipData to rewrittenIntent.")
                rewrittenIntent.clipData = newClipData
                rewrittenIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // If no newClipData was created, clear it to prevent referencing the old inaccessible URIs
                Log.i(TAG, "There was no newClipData - setting the clipData to null.")
                rewrittenIntent.clipData = null
            }
        }

        return rewrittenIntent
    }

    // Copy the file referenced by `uri` to our app's cache directory and return a content URI from
    // our own FileProvider.
    private fun copyFileToCache(uri: Uri): Uri? {
        var filename = uri.lastPathSegment

        // Create a filename if we don't have one
        if (filename == null || filename == "") { filename = "shared_content_${System.currentTimeMillis()}" }

        return try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Could not open input stream to cache shared content - aborting.")
                return null
            }

            val tempFile = File(cacheDir, filename)
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Check that the file exists and is not empty
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.w(TAG, "Failed to copy the file to cache or the file is empty.")
                return null
            }

            // Add the created file to our list so we can clean it up (i.e., delete it) when we're done with it
            cachedIntentFiles.add(tempFile)

            // Uncomment if you're debugging this - but for privacy reasons it's likely not a good idea to print filenames to the console
            //Log.i(TAG, "File copied to cache: ${tempFile.absolutePath}, size=${tempFile.length()} bytes")

            FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file to cache", e)
            null
        }
    }

    private fun getUpgradeDatabaseIntent(): Intent { return getRoutedIntent(DatabaseUpgradeActivity::class.java, getConversationListIntent()) }

    private fun getWelcomeIntent(): Intent { return getRoutedIntent(LandingActivity::class.java, getConversationListIntent()) }

    private fun getConversationListIntent(): Intent { return Intent(this, HomeActivity::class.java) }

    private fun getRoutedIntent(destination: Class<*>?, nextIntent: Intent?): Intent {
        val intent = Intent(this, destination)
        if (nextIntent != null) { intent.putExtra("next_intent", nextIntent) }
        return intent
    }

    private fun initializeClearKeyReceiver() {
        Log.i(TAG, "initializeClearKeyReceiver()")
        this.clearKeyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "onReceive() for clear key event")
                onMasterSecretCleared()
            }
        }

        val filter = IntentFilter(KeyCachingService.CLEAR_KEY_EVENT)
        ContextCompat.registerReceiver(
            this,
            clearKeyReceiver, filter,
            KeyCachingService.KEY_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun removeClearKeyReceiver(context: Context) {
        if (clearKeyReceiver != null) {
            context.unregisterReceiver(clearKeyReceiver)
            clearKeyReceiver = null
        }
    }
}