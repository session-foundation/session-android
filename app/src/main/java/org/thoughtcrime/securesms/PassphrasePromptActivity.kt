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
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.animation.BounceInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
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

class PassphrasePromptActivity : BaseActionBarActivity() {

    // TODO: Put the TAG back when happy
    companion object {
        private val TAG: String = "ACL" // PassphrasePromptActivity::class.java.getSimpleName()
    }

    private var fingerprintPrompt: ImageView?      = null
    private var lockScreenButton: Button?          = null
    private var visibilityToggle: AnimatingToggle? = null

    private var biometricPrompt: BiometricPrompt?       = null
    private var promptInfo: BiometricPrompt.PromptInfo? = null
    private val biometricSecretProvider = BiometricSecretProvider()

    private var authenticated      = false
    private var failure            = false
    private var hasSignatureObject = true

    private var keyCachingService: KeyCachingService? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Creating PassphrasePromptActivity")
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
            .setTitle("Unlock Session") // TODO: Need a string for this, like `lockUnlockSession` -> "Unlock {app_name}" or similar
            .setNegativeButtonText(this.applicationContext.getString(R.string.cancel))
            // If we needed it, we could also add things like `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` here
            .build()
    }

    override fun onResume() {
        super.onResume()
        setLockTypeVisibility()
        if (isScreenLockEnabled(this) && !authenticated && !failure) { resumeScreenLock() }
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
            // TODO: Need a string for this, like `lockUnlockSession` -> "Unlock {app_name}" or similar
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

        // The 'nextIntent' will take us to the MainActivity if this is a standard unlock, or it will
        // take us to the ShareActivity if this is an external share - however, in this latter case
        // Intent.FLAG_GRANT_READ_URI_PERMISSION is only granted to THIS activity, not the share
        // activity, which can cause external sharing to fail on some specific devices such as a
        // Pixel 7a running Android API 35 (while it will work on other devices - very odd!).
        // Regardless, we have to mitigate against this when sharing - so we duplicate and save any
        // sharing intent URI to our local cache, and then share THAT via the sharing activity as
        // a workaround.
        val nextIntent = intent.getParcelableExtra<Intent?>("next_intent")
        if (nextIntent == null) {
            Log.w(TAG, "Got a null nextIntent - cannot proceed.")
        } else {
            startActivity(nextIntent)
        }

        finish()
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