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

import android.animation.Animator
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PorterDuff
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.animation.Animation
import android.view.animation.BounceInterpolator
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
//import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.core.os.CancellationSignal
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockEnabled
import org.session.libsession.utilities.TextSecurePreferences.Companion.setScreenLockTimeout
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphrasePromptActivity.FingerprintListener
import org.thoughtcrime.securesms.components.AnimatingToggle
import org.thoughtcrime.securesms.crypto.BiometricSecretProvider
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.service.KeyCachingService.KeySetBinder
import org.thoughtcrime.securesms.util.AnimationCompleteListener
import java.lang.Exception
import java.security.Signature

class PassphrasePromptActivity : BaseActionBarActivity() {

    companion object {
        private val TAG: String = PassphrasePromptActivity::class.java.getSimpleName()
    }

    private var fingerprintPrompt: ImageView? = null
    private var lockScreenButton: Button? = null

    private var visibilityToggle: AnimatingToggle? = null

    private var fingerprintManager: FingerprintManagerCompat? = null
    private var fingerprintCancellationSignal: CancellationSignal? = null
    private var fingerprintListener: FingerprintListener? = null

    private val biometricSecretProvider = BiometricSecretProvider()

    private var authenticated = false
    private var failure = false
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
                keyCachingService!!.setMasterSecret(Any())
                keyCachingService = null
            }
        }, BIND_AUTO_CREATE)
    }

    public override fun onResume() {
        super.onResume()

        setLockTypeVisibility()

        if (isScreenLockEnabled(this) && !authenticated && !failure) {
            resumeScreenLock()
        }

        failure = false
    }

    public override fun onPause() {
        super.onPause()

        if (isScreenLockEnabled(this)) {
            pauseScreenLock()
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

    private fun handleAuthenticated() {
        authenticated = true
        //TODO Replace with a proper call.
        if (keyCachingService != null) {
            keyCachingService!!.setMasterSecret(Any())
        }

        // Finish and proceed with the next intent.
        val nextIntent = intent.getParcelableExtra<Intent?>("next_intent")
        if (nextIntent != null) {
            try {
                startActivity(nextIntent)
            } catch (e: SecurityException) {
                Log.w(TAG, "Access permission not passed from PassphraseActivity, retry sharing.", e)
            }
        }
        finish()
    }

    private fun initializeResources() {
        val statusTitle = findViewById<TextView?>(R.id.app_lock_status_title)
        if (statusTitle != null) {
            val c = applicationContext
            val lockedTxt = Phrase.from(c, R.string.lockAppLocked)
                .put(APP_NAME_KEY, c.getString(R.string.app_name))
                .format().toString()
            statusTitle.text = lockedTxt
        }

        visibilityToggle = findViewById<AnimatingToggle?>(R.id.button_toggle)
        fingerprintPrompt = findViewById<ImageView>(R.id.fingerprint_auth_container)
        lockScreenButton = findViewById<Button>(R.id.lock_screen_auth_container)
        fingerprintManager = FingerprintManagerCompat.from(this)
        fingerprintCancellationSignal = CancellationSignal()
        fingerprintListener = FingerprintListener()

        fingerprintPrompt!!.setImageResource(R.drawable.ic_fingerprint_white_48dp)
        fingerprintPrompt!!.background.setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN)

        lockScreenButton!!.setOnClickListener(View.OnClickListener { v: View? -> resumeScreenLock() })
    }

    private fun setLockTypeVisibility() {
        if (isScreenLockEnabled(this)) {
            if (fingerprintManager!!.isHardwareDetected() && fingerprintManager!!.hasEnrolledFingerprints()) {
                fingerprintPrompt!!.setVisibility(View.VISIBLE)
                lockScreenButton!!.visibility = View.GONE
            } else {
                fingerprintPrompt!!.setVisibility(View.GONE)
                lockScreenButton!!.visibility = View.VISIBLE
            }
        } else {
            fingerprintPrompt!!.setVisibility(View.GONE)
            lockScreenButton!!.visibility = View.GONE
        }
    }

    private fun resumeScreenLock() {
        val keyguardManager = checkNotNull(getSystemService(KEYGUARD_SERVICE) as KeyguardManager)

        if (!keyguardManager.isKeyguardSecure) {
            Log.w(TAG, "Keyguard not secure...")
            setScreenLockEnabled(applicationContext, false)
            setScreenLockTimeout(applicationContext, 0)
            handleAuthenticated()
            return
        }

        if (fingerprintManager!!.isHardwareDetected() && fingerprintManager!!.hasEnrolledFingerprints()) {
            Log.i(TAG, "Listening for fingerprints...")
            fingerprintCancellationSignal = CancellationSignal()
            var signature: Signature?
            try {
                signature = biometricSecretProvider.getOrCreateBiometricSignature(this)
                hasSignatureObject = true
            } catch (e: Exception) {
                signature = null
                hasSignatureObject = false
                Log.e(TAG, "Error getting / creating signature", e)
            }
            fingerprintManager.authenticate(
                if (signature == null) null else FingerprintManagerCompat.CryptoObject(signature),
                0,
                fingerprintCancellationSignal,
                fingerprintListener,
                null
            )
        } else {
            Log.i(TAG, "firing intent...")
            val intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock Session", "")
            startActivityForResult(intent, 1)
        }
    }

    private fun pauseScreenLock() {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal!!.cancel()
        }
    }

    private inner class FingerprintListener : FingerprintManagerCompat.AuthenticationCallback() {
        override fun onAuthenticationError(errMsgId: Int, errString: CharSequence?) {
            Log.w(TAG, "Authentication error: " + errMsgId + " " + errString)
            onAuthenticationFailed()
        }

        override fun onAuthenticationSucceeded(result: FingerprintManagerCompat.AuthenticationResult) {
            Log.i(TAG, "onAuthenticationSucceeded")
            if (result.getCryptoObject() == null || result.getCryptoObject().getSignature() == null) {
                if (hasSignatureObject) {
                    // authentication failed
                    onAuthenticationFailed()
                } else {
                    fingerprintPrompt!!.setImageResource(R.drawable.ic_check_white_48dp)
                    fingerprintPrompt!!.background.setColorFilter(getResources().getColor(R.color.green_500), PorterDuff.Mode.SRC_IN)

                    fingerprintPrompt!!.animate()
                        .setInterpolator(BounceInterpolator())
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .setDuration(500)
                        .setListener(object : AnimationCompleteListener() {
                            override fun onAnimationEnd(animation: Animator) {
                                handleAuthenticated()

                                fingerprintPrompt!!.setImageResource(R.drawable.ic_fingerprint_white_48dp)
                                fingerprintPrompt!!.background.setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN)
                            }
                        })
                        .start()
                }
                return
            }
            // Signature object now successfully unlocked
            var authenticationSucceeded = false
            try {
                val signature = result.getCryptoObject().getSignature()
                val random = biometricSecretProvider.getRandomData()
                signature!!.update(random)
                val signed = signature.sign()
                authenticationSucceeded = biometricSecretProvider.verifySignature(random, signed)
            } catch (e: Exception) {
                Log.e(TAG, "onAuthentication signature generation and verification failed", e)
            }
            if (!authenticationSucceeded) {
                onAuthenticationFailed()
                return
            }

            fingerprintPrompt!!.setImageResource(R.drawable.ic_check_white_48dp)
            fingerprintPrompt!!.background.setColorFilter(getResources().getColor(R.color.green_500), PorterDuff.Mode.SRC_IN)
            fingerprintPrompt!!.animate().setInterpolator(BounceInterpolator()).scaleX(1.1f).scaleY(1.1f).setDuration(500).setListener(object : AnimationCompleteListener() {
                override fun onAnimationEnd(animation: Animator) {
                    handleAuthenticated()

                    fingerprintPrompt!!.setImageResource(R.drawable.ic_fingerprint_white_48dp)
                    fingerprintPrompt!!.background.setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN)
                }
            }).start()
        }

        override fun onAuthenticationFailed() {
            Log.w(TAG, "onAuthenticationFailed()")

            fingerprintPrompt!!.setImageResource(R.drawable.ic_close_white_48dp)
            fingerprintPrompt!!.background.setColorFilter(getResources().getColor(R.color.red_500), PorterDuff.Mode.SRC_IN)

            val shake = TranslateAnimation(0f, 30f, 0f, 0f)
            shake.setDuration(50)
            shake.setRepeatCount(7)
            shake.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}

                override fun onAnimationEnd(animation: Animation?) {
                    fingerprintPrompt!!.setImageResource(R.drawable.ic_fingerprint_white_48dp)
                    fingerprintPrompt!!.getBackground().setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN)
                }

                override fun onAnimationRepeat(animation: Animation?) {}
            })

            fingerprintPrompt!!.startAnimation(shake)
        }
    }


}