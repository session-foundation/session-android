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
import android.view.animation.Animation
import android.view.animation.BounceInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import com.squareup.phrase.Phrase
import java.lang.Exception
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockTimeout
import org.session.libsession.utilities.ThemeUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.crypto.BiometricSecretProvider
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.service.KeyCachingService.KeySetBinder

class ScreenLockActivity : BaseActionBarActivity() {
    private val TAG: String = ScreenLockActivity::class.java.simpleName

    private lateinit var fingerprintPrompt: ImageView

    private var biometricPrompt: BiometricPrompt?       = null
    private var promptInfo: BiometricPrompt.PromptInfo? = null
    private val biometricSecretProvider = BiometricSecretProvider()

    private var authenticated      = false
    private var failure            = false
    private var hasSignatureObject = true

    private var keyCachingService: KeyCachingService? = null

    private var accentColor: Int = -1
    private var errorColor:  Int = -1

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Creating ScreenLockActivity")
        super.onCreate(savedInstanceState)

        accentColor = ThemeUtil.getThemedColor(this, R.attr.accentColor)
        errorColor  = ThemeUtil.getThemedColor(this, R.attr.danger)

        setContentView(R.layout.screen_lock_activity)
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
        val context = this
        val executor = ContextCompat.getMainExecutor(context)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Authentication error: $errorCode $errString")

                when (errorCode) {
                    // User cancelled the biometric overlay by clicking "Cancel" or clicking off it
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED -> {
                        onAuthenticationFailed()
                        finish()
                    }

                    // User made 5 incorrect biometric login attempts so they get a timeout
                    // Note: The SYSTEM provides the localised error "Too many attempts. Try again later.".
                    BiometricPrompt.ERROR_LOCKOUT -> {
                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                        finish()
                    }

                    // User made a large number of incorrect biometric login attempts and Android disabled
                    // the fingerprint sensor until they lock the device then log back in via non-biometric means.
                    // Note: The SYSTEM provides the localised error "Too many attempts. Fingerprint sensor disabled."
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                        finish()
                    }

                    else -> {
                        Log.w(TAG, "Unhandled authentication error: $errorCode $errString")
                        finish()
                    }
                }
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "onAuthenticationFailed()")
                showAuthenticationFailedUI()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "onAuthenticationSucceeded")

                val signature = result.cryptoObject?.signature

                if (signature == null) {
                    // If we expected a signature but didn't get one, treat this as failure
                    if (hasSignatureObject) {
                        onAuthenticationFailed()
                    } else {
                        // If there was no signature needed, handle as success
                        showAuthenticationSuccessUI()
                    }
                    return
                }

                // Perform signature verification
                try {
                    val random = biometricSecretProvider.getRandomData()
                    signature.update(random)
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
            .setTitle("Unlock Session") // TODO: Need a string for this, like `lockAppUnlock` -> "Unlock {app_name}" or similar - have informed Rebecca
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
            // TODO: Need a string for this, like `lockAppUnlock` -> "Unlock {app_name}" or similar - have informed Rebecca
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
        fingerprintPrompt.setImageResource(R.drawable.ic_x)
        fingerprintPrompt.background?.setColorFilter(errorColor, PorterDuff.Mode.SRC_IN)

        // Define and perform a "shake" animation on authentication failed
        val shake = TranslateAnimation(0f, 30f, 0f, 0f).apply {
            duration = 50
            repeatCount = 7
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}

                override fun onAnimationEnd(animation: Animation?) {
                    fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp)
                    fingerprintPrompt.background?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                }

                override fun onAnimationRepeat(animation: Animation?) {}
            })
        }
        fingerprintPrompt.startAnimation(shake)
    }

    private fun showAuthenticationSuccessUI() {
        Log.i(TAG, "Authentication successful.")

        fingerprintPrompt.setImageResource(R.drawable.ic_check)
        fingerprintPrompt.background?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)

        // Animate and call handleAuthenticated() on animation end
        fingerprintPrompt.animate()
            ?.setInterpolator(BounceInterpolator())
            ?.scaleX(1.1f)
            ?.scaleY(1.1f)
            ?.setDuration(500)
            ?.withEndAction {
                fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp)
                fingerprintPrompt.background?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                handleAuthenticated()
            }
            ?.start()
    }

    private fun handleAuthenticated() {
        authenticated = true
        keyCachingService?.setMasterSecret(Any())

        // The 'nextIntent' will take us to the MainActivity if this is a standard unlock, or it will
        // take us to the ShareActivity if this is an external share.
        val nextIntent = intent.getParcelableExtra<Intent?>("next_intent")
        if (nextIntent == null) {
            Log.w(TAG, "Got a null nextIntent - cannot proceed.")
        } else {
            startActivity(nextIntent)
        }

        finish()
    }

    private fun setLockTypeVisibility() {
        val screenLockEnabled = TextSecurePreferences.isScreenLockEnabled(this)
        val biometricManager = BiometricManager.from(this)
        val authenticationPossible = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        fingerprintPrompt.visibility = if (screenLockEnabled && authenticationPossible) View.VISIBLE else View.GONE
    }

    private fun initializeResources() {
        val statusTitle = findViewById<TextView>(R.id.app_lock_status_title)
        statusTitle?.text = Phrase.from(applicationContext, R.string.lockAppLocked)
            .put(APP_NAME_KEY, getString(R.string.app_name))
            .format().toString()

        fingerprintPrompt = findViewById(R.id.fingerprint_auth_container)

        fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp)
        fingerprintPrompt.background?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
    }
}