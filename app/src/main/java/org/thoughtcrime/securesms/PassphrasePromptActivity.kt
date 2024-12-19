/*
 * Copyright (C) 2011 Whisper Systems
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

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.animation.BounceInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.squareup.phrase.Phrase
import java.lang.Exception
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockTimeout
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.components.AnimatingToggle
import org.thoughtcrime.securesms.crypto.BiometricSecretProvider
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.service.KeyCachingService.KeySetBinder
import java.io.File
import java.io.FileOutputStream

class PassphrasePromptActivity : BaseActionBarActivity() {

    // TODO: Put the TAG back when happy
    companion object {
        private val TAG: String = "ACL" // PassphrasePromptActivity::class.java.getSimpleName()
    }

    private var fingerprintPrompt: ImageView? = null
    private var lockScreenButton: Button? = null

    private var visibilityToggle: AnimatingToggle? = null

    private var biometricPrompt: BiometricPrompt? = null
    private var promptInfo: BiometricPrompt. PromptInfo? = null
    private val biometricSecretProvider = BiometricSecretProvider()

    private var authenticated      = false
    private var failure            = false
    private var hasSignatureObject = true

    private var keyCachingService: KeyCachingService? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.prompt_passphrase_activity)
        initializeResources()

        // Start and bind to the KeyCachingService instance.
        val bindIntent = Intent(this, KeyCachingService::class.java)
        startService(bindIntent)
        bindService(bindIntent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                keyCachingService = (service as KeySetBinder).service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                keyCachingService?.setMasterSecret(Any())
                keyCachingService = null
            }
        }, BIND_AUTO_CREATE)

        // Set up biometric prompt and prompt info
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Authentication error: $errorCode $errString")
                onAuthenticationFailed()
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "onAuthenticationFailed()")
                showAuthenticationFailedUI()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "onAuthenticationSucceeded")
                val cryptoObject = result.cryptoObject
                val signature = cryptoObject?.signature
                if (signature == null && hasSignatureObject) {
                    // If we expected a signature but didn't get one, treat this as failure
                    onAuthenticationFailed()
                    return
                } else if (signature == null && !hasSignatureObject) {
                    // If there was no signature needed we can handle this as success
                    showAuthenticationSuccessUI()
                    return
                }

                // Perform signature verification as before
                try {
                    val random = biometricSecretProvider.getRandomData()
                    signature!!.update(random)
                    val signed = signature.sign()
                    val verified = biometricSecretProvider.verifySignature(random, signed)

                    if (!verified) {
                        onAuthenticationFailed()
                        return
                    }

                    showAuthenticationSuccessUI()
                } catch (e: Exception) {
                    Log.e(TAG, "Signature verification failed", e)
                    onAuthenticationFailed()
                }
            }
        })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Session")
            .setNegativeButtonText("Cancel")
            // If we needed it, we could also add things like `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` here
            .build()
    }

    override fun onResume() {
        super.onResume()
        setLockTypeVisibility()

        if (isScreenLockEnabled(this) && !authenticated && !failure) {
            resumeScreenLock()
        }

        failure = false
    }

    override fun onPause() {
        super.onPause()
        // If needed, cancel authentication:
        biometricPrompt?.cancelAuthentication()
    }

    private fun resumeScreenLock() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        // Note: `isKeyguardSecure` just returns whether the keyguard is locked via a pin, pattern,
        // or password - in which case it's actually correct to allow the user in, as we have nothing
        // to authenticate against! (we use the system authentication - not our own custom auth.).
        if (!keyguardManager.isKeyguardSecure) {
            Log.w(TAG, "Keyguard not secure...")
            setScreenLockEnabled(applicationContext, false)
            setScreenLockTimeout(applicationContext, 0)
            handleAuthenticated()
            return
        }

        // Attempt to get a signature for biometric authentication
        val signature = biometricSecretProvider.getOrCreateBiometricSignature(this)
        hasSignatureObject = (signature != null)

        if (signature != null) {
            // Biometrics are enrolled and the key is available
            val cryptoObject = BiometricPrompt.CryptoObject(signature)
            biometricPrompt?.authenticate(promptInfo!!, cryptoObject)
        } else {
            // No biometric key available (no biometrics enrolled or key cannot be created)
            // Fallback to device credentials (PIN, pattern, or password)
            val intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock Session", "")
            startActivityForResult(intent, 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    public override fun onActivityResult(requestCode: Int, resultcode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultcode, data)
        if (requestCode != 1) return

        if (resultcode == RESULT_OK) {
            handleAuthenticated()
        } else {
            Log.w(TAG, "Authentication failed")
            failure = true
        }
    }

    private fun showAuthenticationFailedUI() {
        fingerprintPrompt?.setImageResource(R.drawable.ic_close_white_48dp)
        fingerprintPrompt?.background?.setColorFilter(resources.getColor(R.color.red_500), PorterDuff.Mode.SRC_IN)
        // Note: We can apply a 'shake' animation here if we wish
    }

    private fun showAuthenticationSuccessUI() {
        fingerprintPrompt?.setImageResource(R.drawable.ic_check_white_48dp)
        fingerprintPrompt?.background?.setColorFilter(resources.getColor(R.color.green_500), PorterDuff.Mode.SRC_IN)
        // Animate and call handleAuthenticated() on animation end
        fingerprintPrompt?.animate()
            ?.setInterpolator(BounceInterpolator())
            ?.scaleX(1.1f)
            ?.scaleY(1.1f)
            ?.setDuration(500)
            ?.withEndAction {
                handleAuthenticated()
            }?.start()
    }

    private fun handleAuthenticated() {
        authenticated = true
        keyCachingService?.setMasterSecret(Any())
        val nextIntent = intent.getParcelableExtra<Intent?>("next_intent")
        if (nextIntent == null) {
            Log.w(TAG, "Got a null nextIntent - cannot proceed.")
            finish()
        }

        // Are we sharing something or just unlocking the device? We'll assume sharing for now.
        var intentRegardsExternalSharing = true

        val bundle = intent.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                Log.w(TAG, "We can see an extra with key $key and value: $value")

                // If this is just a standard fingerprint unlock and not sharing anything proceed to Main
                if (value is Intent && value.action == "android.intent.action.MAIN") {
                    intentRegardsExternalSharing = false
                    break
                }
            }
        }

        try {
            if (intentRegardsExternalSharing) {
                // Attempt to rewrite any URIs from clipData into our own FileProvider
                val rewrittenIntent = rewriteShareIntentUris(nextIntent!!)
                startActivity(rewrittenIntent)
            } else {
                startActivity(nextIntent)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Access permission not passed from PassphraseActivity, retry sharing.", e)
        }

        finish()
    }

    // Rewrite the original share Intent, copying any URIs it contains to our app's private cache,
    // and return a new "rewritten" Intent that references the local copies of URIs via our FileProvider.
    private fun rewriteShareIntentUris(originalIntent: Intent): Intent {
        val rewrittenIntent = Intent(originalIntent)
        val originalClipData = originalIntent.clipData

        originalClipData?.let { clipData ->
            var newClipData: ClipData? = null

            for (i in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(i)
                val originalUri = item.uri

                if (originalUri != null) {
                    // First, copy the file locally.
                    val localUri = copyFileToCache(originalUri)

                    if (localUri != null) {
                        // Create a ClipData from the localUri, not the originalUri!
                        if (newClipData == null) {
                            newClipData = ClipData.newUri(contentResolver, "Shared Content", localUri)
                        } else {
                            newClipData.addItem(ClipData.Item(localUri))
                        }
                    } else {
                        // If copying fails, handle gracefully.
                        // Ideally, don't fallback to originalUri because that may cause SecurityException again.
                        Log.e(TAG, "Could not rewrite URI: $originalUri")
                    }
                }
            }

            if (newClipData != null) {
                rewrittenIntent.clipData = newClipData
                rewrittenIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // If no newClipData was created, clear it to prevent referencing the old inaccessible URIs
                rewrittenIntent.clipData = null
            }
        }

        return rewrittenIntent
    }

    /**
     * Copies the file referenced by [uri] to our app's cache directory and returns
     * a content URI from our own FileProvider.
     */
    private fun copyFileToCache(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Could not open input stream to cache shared content - aborting.")
                return null
            }

            val tempFile = File(cacheDir, "shared_content_${System.currentTimeMillis()}")
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

            Log.i(TAG, "File copied to cache: ${tempFile.absolutePath}, size=${tempFile.length()} bytes")

            // Return a URI from our FileProvider
            FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file to cache", e)
            null
        }
    }

    private fun setLockTypeVisibility() {
        // Show/hide UI depending on user’s screen lock preference.
        if (isScreenLockEnabled(this)) {
            fingerprintPrompt?.visibility = View.VISIBLE
            lockScreenButton?.visibility  = View.GONE
        } else {
            fingerprintPrompt?.visibility = View.GONE
            lockScreenButton?.visibility  = View.GONE
        }
    }

    private fun initializeResources() {
        val statusTitle = findViewById<TextView>(R.id.app_lock_status_title)
        statusTitle?.text = Phrase.from(applicationContext, R.string.lockAppLocked)
            .put(APP_NAME_KEY, getString(R.string.app_name))
            .format().toString()

        visibilityToggle  = findViewById(R.id.button_toggle)
        fingerprintPrompt = findViewById(R.id.fingerprint_auth_container)
        lockScreenButton  = findViewById(R.id.lock_screen_auth_container)

        fingerprintPrompt?.setImageResource(R.drawable.ic_fingerprint_white_48dp)
        fingerprintPrompt?.background?.setColorFilter(resources.getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN)

        lockScreenButton?.setOnClickListener { resumeScreenLock() }
    }
}