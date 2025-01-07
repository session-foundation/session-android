package org.thoughtcrime.securesms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
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

    companion object {
        private val TAG = ScreenLockActionBarActivity::class.java.simpleName

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
            val numFilesToDelete = cachedIntentFiles.size
            var numFilesDeleted = 0
            for (file in cachedIntentFiles) {
                if (file.exists()) {
                    val success = file.delete()
                    if (success) { numFilesDeleted++ }
                }
            }
            if (numFilesDeleted < numFilesToDelete) {
                val failCount = numFilesToDelete - numFilesDeleted
                Log.w(TAG, "Failed to delete $failCount cached shared file(s).")
            } else if (numFilesToDelete > 0 && numFilesDeleted == numFilesToDelete) {
                Log.i(TAG, "Cached shared files deleted.")
            }
            cachedIntentFiles.clear()
        }
    }

    private var clearKeyReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "ScreenLockActionBarActivity.onCreate(" + savedInstanceState + ")")
        super.onCreate(savedInstanceState)

        val locked = KeyCachingService.isLocked(this) && isScreenLockEnabled(this) && getLocalNumber(this) != null
        routeApplicationState(locked)

        if (!isFinishing) {
            initializeClearKeyReceiver()
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
        Log.i(TAG, "routeApplicationState() - ${getStateName(state)}")

        return when (state) {
            STATE_SCREEN_LOCKED    -> getScreenUnlockIntent()
            STATE_UPGRADE_DATABASE -> getUpgradeDatabaseIntent()
            STATE_WELCOME_SCREEN   -> getWelcomeIntent()
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

    private fun getScreenUnlockIntent(): Intent {
        // If this is an attempt to externally share something while the app is locked then we need
        // to rewrite the intent to reference a cached copy of the shared file.
        // Note: We CANNOT just add `Intent.FLAG_GRANT_READ_URI_PERMISSION` to this intent as we
        // pass it around because we don't have permission to do that (i.e., it doesn't work).
        if (intent.action == "android.intent.action.SEND") {
            val rewrittenIntent = rewriteShareIntentUris(intent)
            return getRoutedIntent(ScreenLockActivity::class.java, rewrittenIntent)
        } else {
            return getRoutedIntent(ScreenLockActivity::class.java, intent)
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

    // Unused at present - but useful for debugging!
    private fun printIntentClipData(i: Intent, prefix: String = "") {
        i.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(i)
                if (item.uri != null) { Log.i(TAG, "${prefix}: Item $i has uri: ${item.uri}") }
                if (item.text != null) { Log.i(TAG, "${prefix}: Item $i has text: ${item.text}") }
            }
        }
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null

        // If we're dealing with a content URI, query the provider to get the actual file name
        if (uri.scheme.equals("content", ignoreCase = true)) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    result = cursor.getString(nameIndex)
                }
            }
        }

        // If we still don't have a name, fallback to the Uri path
        if (result.isNullOrEmpty()) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }

        return result
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

                    // ..then grab the real filename, using a fallback if we couldn't get it from the original Uri..
                    val fileName = getFileNameFromUri(this, originalUri) ?: "Shared Content"

                    if (localUri != null) {
                        // ..then create the new ClipData with the localUri and filename
                        if (newClipData == null) {
                            newClipData = ClipData.newUri(contentResolver, fileName, localUri)

                            // Make sure to also set the "android.intent.extra.STREAM" extra
                            rewrittenIntent.putExtra(extraKey, localUri)
                        } else {
                            newClipData.addItem(ClipData.Item(localUri))
                        }
                    } else {
                        Log.e(TAG, "Could not rewrite Uri - bailing.")
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

    private fun copyFileToCache(uri: Uri): Uri? {
        // Get the actual display name if possible
        val fileName = getFileNameFromUri(this, uri) ?: "shared_content_${System.currentTimeMillis()}"

        return try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Could not open input stream to cache shared content - aborting.")
                return null
            }

            // Create a File in your cache directory using the retrieved name
            val tempFile = File(cacheDir, fileName)
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Verify the file actually exists and isn't empty
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.w(TAG, "Failed to copy the file to cache or the file is empty.")
                return null
            }

            // Record the file so you can delete it when you're done
            cachedIntentFiles.add(tempFile)

            // Return a FileProvider Uri that references this cached file
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