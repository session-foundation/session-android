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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.onboarding.landing.LandingActivity
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.FileProviderUtil
import org.thoughtcrime.securesms.util.FilenameUtils

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

        // OLD:
        val locked = KeyCachingService.isLocked(this) && isScreenLockEnabled(this) && getLocalNumber(this) != null
        routeApplicationState(locked)

        super.onCreate(savedInstanceState)

        // NEW:
        //super.onCreate(savedInstanceState) // Ensure the activity lifecycle starts properly

//        lifecycleScope.launch {
//            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
//                val locked = withContext(Dispatchers.IO) {
//                    KeyCachingService.isLocked(this@ScreenLockActionBarActivity) &&
//                            isScreenLockEnabled(this@ScreenLockActionBarActivity) &&
//                            getLocalNumber(this@ScreenLockActionBarActivity) != null
//                }
//                routeApplicationState(locked) // Call this only after the value is computed
//            }
//        }

//        val locked = runBlocking(Dispatchers.IO) { // Run on IO but block until done
//            KeyCachingService.isLocked(this@ScreenLockActionBarActivity) &&
//                    isScreenLockEnabled(this@ScreenLockActionBarActivity) &&
//                    getLocalNumber(this@ScreenLockActionBarActivity) != null
//        }
//        routeApplicationState(locked)
//
//        super.onCreate(savedInstanceState)

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

        lifecycleScope.launch {
            val state = getApplicationState(locked)

            // Note: getIntentForState is suspend because it _may_ perform a file copy for any
            // incoming file to be shared - so we do this off the main thread.
            val intent = getIntentForState(state)

            if (intent != null) {
                startActivity(intent)
                finish()
            }
        }
    }

    private suspend fun getIntentForState(state: Int): Intent? {
        Log.i(TAG, "routeApplicationState() - ${getStateName(state)}")

        return when (state) {
            STATE_SCREEN_LOCKED    -> getScreenUnlockIntent() // Note: This is a suspend function
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

    private suspend fun getScreenUnlockIntent(): Intent {
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

    // Rewrite the original share Intent, copying any URIs it contains to our app's private cache,
    // and return a new "rewritten" Intent that references the local copies of URIs via our FileProvider.
    // We do this to prevent a SecurityException being thrown regarding ephemeral permissions to
    // view the shared URI which may be available to THIS ScreenLockActivity, but which is NOT
    // then valid on the actual ShareActivity which we transfer the Intent through to. With a
    // rewritten copy of the original Intent that references our own cached copy of the URI we have
    // full control over it.
    // Note: We delete any cached file(s) in ConversationActivity.onDestroy.
    private suspend fun rewriteShareIntentUris(originalIntent: Intent): Intent? = withContext(Dispatchers.IO) {
        val rewrittenIntent = Intent(originalIntent)

        // Clear original clipData
        rewrittenIntent.clipData = null

        // If we couldn't find one then we have nothing to re-write and we'll just return the original intent
        if (!rewrittenIntent.hasExtra(Intent.EXTRA_STREAM)) {
            Log.i(TAG, "No stream to rewrite - returning original intent")
            return@withContext originalIntent
        }

        // Grab and rewrite the original intent's clipData - adding it to our rewrittenIntent as we go
        val originalClipData = originalIntent.clipData
        originalClipData?.let { clipData ->
            var newClipData: ClipData? = null
            for (i in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(i)
                val originalUri = item.uri

                if (originalUri != null) {
                    // Get a suitable filename and copy the file to our cache directory..
                    val filename = FilenameUtils.getFilenameFromUri(this@ScreenLockActionBarActivity, originalUri)
                    val localUri = copyFileToCache(originalUri, filename)

                    if (localUri != null) {
                        // ..then create the new ClipData with the localUri and filename.
                        if (newClipData == null) {
                            newClipData = ClipData.newUri(contentResolver, filename, localUri)

                            // Make sure to also set the "android.intent.extra.STREAM" extra
                            rewrittenIntent.putExtra(Intent.EXTRA_STREAM, localUri)
                        } else {
                            newClipData.addItem(ClipData.Item(localUri))
                        }
                    } else {
                        Log.e(TAG, "Could not rewrite Uri - bailing.")
                        return@withContext null
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

        rewrittenIntent
    }

    private suspend fun copyFileToCache(uri: Uri, filename: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Could not open input stream to cache shared content - aborting.")
                return@withContext null
            }

            // Create a File in your cache directory using the retrieved name
            val tempFile = File(cacheDir, filename)
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Verify the file actually exists and isn't empty
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.w(TAG, "Failed to copy the file to cache or the file is empty.")
                return@withContext null
            }

            // Record the file so you can delete it when you're done
            cachedIntentFiles.add(tempFile)

            // Return a FileProvider Uri that references this cached file
            FileProvider.getUriForFile(this@ScreenLockActionBarActivity, FileProviderUtil.AUTHORITY, tempFile)
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