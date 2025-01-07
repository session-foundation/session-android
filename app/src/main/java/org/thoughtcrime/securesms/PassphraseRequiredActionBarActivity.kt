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
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.onboarding.landing.LandingActivity
import org.thoughtcrime.securesms.service.KeyCachingService
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.util.Locale

//TODO AC: Rename to ScreenLockActionBarActivity.
abstract class PassphraseRequiredActionBarActivity : BaseActionBarActivity() {

    companion object {
        // TODO: Put back tag when happy
        private const val TAG = "ACL" //PassphraseRequiredActionBarActivity.class.getSimpleName();

        const val LOCALE_EXTRA: String = "locale_extra"

        private const val STATE_NORMAL            = 0
        private const val STATE_PROMPT_PASSPHRASE = 1 //TODO AC: Rename to STATE_SCREEN_LOCKED
        private const val STATE_UPGRADE_DATABASE  = 2 //TODO AC: Rename to STATE_MIGRATE_DATA
        private const val STATE_WELCOME_SCREEN    = 3

        // If we're sharing files we need to cache the data from the share Intent to maintain control of it
        private val cachedIntentFiles = mutableListOf<File>()

        // TODO: We need to call this - but if we call it in onDestroy that may be too soon because we actually want to use it..... leaving it for now.
        fun cleanupCreatedFiles() {
            for (file in cachedIntentFiles) {
                if (file.exists()) {
                    Log.i(TAG, "Deleting: " + file.path)
                    file.delete()
                }
            }
            cachedIntentFiles.clear()
        }
    }

    private var clearKeyReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Hit PassphraseRequiredActionBarActivity.onCreate(" + savedInstanceState + ")")
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            val externalShareIntent = savedInstanceState.getParcelable<Intent?>("next_intent")
            if (externalShareIntent == null) {
                Log.w(TAG, "Got a null externalShareIntent!")
                //finish()
            } else {
                Log.w(TAG, "Got a NON-NULL externalShareIntent - we can work with this!")
            }
        }

        /*
        // 1) Check if we have a share intent
        if (intent?.action == Intent.ACTION_SEND) {
            Log.w(TAG, "We received an external share request!")

            // 2) Rewrite ephemeral URIs immediately
            val rewrittenIntent = rewriteShareIntentUris(intent)
            if (rewrittenIntent == null) {
                // If rewriting failed (e.g. openInputStream() SecurityException) there's no point in continuing
                Log.e(TAG, "Rewriting ephemeral URIs failed - cannot proceed.")
                finish()
                return
            }

            // 3) Now we have URIs that point to our own FileProvider
            // Next we decide: is the user locked (needs passphrase) or not?
            val locked = KeyCachingService.isLocked(this) && isScreenLockEnabled(this) && getLocalNumber(this) != null
            if (locked) {
                // Go to passphrase prompt, and embed the "rewritten" share Intent
                val promptIntent = getRoutedIntent(PassphrasePromptActivity::class.java, rewrittenIntent)
                startActivity(promptIntent)
                finish()
                return
            } else {
                // Already unlocked, so go straight to the final "ShareActivity"
                val shareIntent = Intent(this, ShareActivity::class.java).apply {
                    putExtra("rewritten_intent", rewrittenIntent)
                }
                startActivity(shareIntent)
                finish()
                return
            }
        }
        */

        val locked = KeyCachingService.isLocked(this) && isScreenLockEnabled(this) && getLocalNumber(this) != null
        routeApplicationState(locked)

        //super.onCreate(savedInstanceState);
        if (!isFinishing) {
            initializeClearKeyReceiver()
            Log.w(TAG, "We aren't finishing so calling onCreate(savedInstanceState, true);")
            onCreate(savedInstanceState, true)
        }
    }

    protected open fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {}

    override fun onPause() {
        Log.i(TAG, "onPause()")
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "Hit PassphraseRequiredActionBarActivity.onDestroy()")
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
        Log.i(TAG, "routeApplicationState(), state: $state")

        return when (state) {
            STATE_PROMPT_PASSPHRASE -> {
                Log.i(TAG, "Routing intent to getPromptPassphraseIntent")
                getPromptPassphraseIntent()
            }
            STATE_UPGRADE_DATABASE  -> {
                Log.i(TAG, "Routing intent to getUpgradeDatabaseIntent")
                getUpgradeDatabaseIntent()
            }
            STATE_WELCOME_SCREEN    -> {
                Log.i(TAG, "Routing intent to getWelcomeIntent")
                getWelcomeIntent()
            }
            else -> {
                Log.i(TAG, "Not routing intent in `getIntentForState` - returning null")
                null
            }
        }
    }

    private fun getApplicationState(locked: Boolean): Int {
        return if (getLocalNumber(this) == null) {
            STATE_WELCOME_SCREEN
        } else if (locked) {
            STATE_PROMPT_PASSPHRASE
        } else if (DatabaseUpgradeActivity.isUpdate(this)) {
            STATE_UPGRADE_DATABASE
        } else {
            STATE_NORMAL
        }
    }

    private fun getPromptPassphraseIntent(): Intent {
        val i = intent
        val b = i.extras
        if (b != null) {
            for (key in b.keySet()) {
                Log.w(TAG, "PRABA-OG-intent >> Key: " + key + " --> " + b.get(key))
            }
        }

        // If this is an attempt to externally share something while the app is locked then we need
        // to write the intent to our own local storage - otherwise the ephemeral permission expires.
        // Note: We CANNOT just add `Intent.FLAG_GRANT_READ_URI_PERMISSION` to this intent as we
        // pass it around because we don't have permission to do that (i.e., it doesn't work).
        Log.w(TAG, "Action is: " + i.action)
        if (i.action === "android.intent.action.SEND") {
            Log.w(TAG, "Intent action is to SEND - re-writing Intent!")

            val rewrittenIntent = rewriteShareIntentUris(i)
            if (rewrittenIntent != null) {
                val b2 = rewrittenIntent.extras
                if (b2 != null) {
                    for (key in b2.keySet()) {
                        Log.w(TAG, "PRABA-Rewritten-intent >> Key: " + key + " --> " + b2.get(key))
                    }
                }
            } else {
                Log.i(TAG, "Rewritten intent was NULLLLLLLLLLLLLLLLLl!")
            }

            return getRoutedIntent(PassphrasePromptActivity::class.java, rewrittenIntent)
        } else {
            return getRoutedIntent(PassphrasePromptActivity::class.java, i)
        }
    }

    // Rewrite the original share Intent, copying any URIs it contains to our app's private cache,
    // and return a new "rewritten" Intent that references the local copies of URIs via our FileProvider.
    // We do this to prevent a SecurityException being thrown regarding ephemeral permissions to
    // view the shared URI which may be available to THIS PassphrasePromptActivity, but which is NOT
    // then valid on the actual ShareActivity which we transfer the Intent through to. With a
    // rewritten copy of the original Intent that references our own cached copy of the URI we have
    // full control to grant Intent.FLAG_GRANT_READ_URI_PERMISSION to any activity we wish.
    private fun rewriteShareIntentUris(originalIntent: Intent): Intent? {
        val rewrittenIntent = Intent(originalIntent)
        rewrittenIntent.clipData = null // Clear original clipData


        for (ek: String in rewrittenIntent.extras?.keySet()!!) {
            Log.i(TAG, "EK is: " + ek)
        }

        //val extraKey = rewrittenIntent.extras?.keySet()?.first()
        //val extraKey: String? = rewrittenIntent.extras?.keySet()?.takeIf { it.toString() == "android.intent.extra.STREAM" }?.first()

        // Take the first extra key which relates to a file stream
        val extraKey: String? = rewrittenIntent.extras
            ?.keySet()
            ?.firstOrNull { it == "android.intent.extra.STREAM" }

        // If we couldn't find one then we have nothing to re-write and we'll just return the original intent
        if (extraKey == null) {
            Log.i(TAG, "No stream to rewrite - returning original intent")
            return originalIntent
        }

        val streamUriPath = rewrittenIntent.extras?.getString(extraKey)
        Log.i(TAG, "Stream URI path is: " + streamUriPath)

        if (streamUriPath?.startsWith("content://com.android.providers.downloads") == true) {
            Log.i(TAG, "Shared file stream is a local file - no need to cache & rewrite - returning original intent.")
            return originalIntent
        }

        val originalClipData = originalIntent.clipData

        originalClipData?.let { clipData ->
            Log.i(TAG, "Found clipData to rewrite.")
            var newClipData: ClipData? = null

            Log.i(TAG, "Looking at extraKey: " + extraKey + "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

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

                            if (extraKey != null) {
                                val rewrittenPath: String? = localUri.path
                                Log.i(TAG, "Rewritten path is: " + rewrittenPath)

                                // CAREFUL: Do NOT put the localUri path in the extra - put the localUri itself!
                                rewrittenIntent.putExtra(extraKey, localUri)
                            }
                        } else {
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



    private fun getFileExtension(filePath: String): String {
        val extension = filePath.substringAfterLast('.', "")
        return if (extension.length in 1..4) ".$extension" else ""
    }

    // Copy the file referenced by `uri` to our app's cache directory and return a content URI from
    // our own FileProvider.
    private fun copyFileToCache(uri: Uri): Uri? {
        Log.i(TAG, "copyFileToCache: Incoming OG uri: " + uri.path)
        val fileExtension = if (uri.path == null) "" else getFileExtension(uri.path!!)

        var filename = uri.lastPathSegment
        if (filename == null || filename == "") {
            filename = "shared_content_${System.currentTimeMillis()}"
        }
        Log.i(TAG, "Actual filename: " + filename)

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

            Log.i(TAG, "File copied to cache: ${tempFile.absolutePath}, size=${tempFile.length()} bytes")

            // Return a URI from our FileProvider
            val rewrittenUri = "$packageName.fileprovider"
            Log.i(TAG, "Rewritten URI is: " + rewrittenUri)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file to cache", e)
            null
        }
    }

    private fun getUpgradeDatabaseIntent(): Intent {
        return getRoutedIntent(DatabaseUpgradeActivity::class.java, getConversationListIntent())
    }

    private fun getWelcomeIntent(): Intent {
        return getRoutedIntent(LandingActivity::class.java, getConversationListIntent())
    }

    private fun getConversationListIntent(): Intent {
        return Intent(this, HomeActivity::class.java)
    }

    private fun getRoutedIntent(destination: Class<*>?, nextIntent: Intent?): Intent {
        val intent = Intent(this, destination)
        if (nextIntent != null) {
            intent.putExtra("next_intent", nextIntent)
        }
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
