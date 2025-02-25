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
package org.thoughtcrime.securesms;

import static org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY;

import android.animation.Animator;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.os.CancellationSignal;
import com.squareup.phrase.Phrase;
import java.security.Signature;
import network.loki.messenger.R;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.components.AnimatingToggle;
import org.thoughtcrime.securesms.crypto.BiometricSecretProvider;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.AnimationCompleteListener;

//TODO Rename to ScreenLockActivity and refactor to Kotlin.
public class PassphrasePromptActivity extends BaseActionBarActivity {

  private static final String TAG = PassphrasePromptActivity.class.getSimpleName();

  private ImageView              fingerprintPrompt;
  private Button                 lockScreenButton;

  private AnimatingToggle visibilityToggle;

  private FingerprintManagerCompat fingerprintManager;
  private CancellationSignal       fingerprintCancellationSignal;
  private FingerprintListener      fingerprintListener;

  private final BiometricSecretProvider biometricSecretProvider = new BiometricSecretProvider();

  private boolean authenticated;
  private boolean failure;
  private boolean hasSignatureObject = true;

  private KeyCachingService keyCachingService;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.i(TAG, "onCreate()");
    super.onCreate(savedInstanceState);

    setContentView(R.layout.prompt_passphrase_activity);
    initializeResources();

    // Start and bind to the KeyCachingService instance.
    Intent bindIntent = new Intent(this, KeyCachingService.class);
    startService(bindIntent);
    bindService(bindIntent, new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder service) {
        keyCachingService = ((KeyCachingService.KeySetBinder)service).getService();
      }
      @Override
      public void onServiceDisconnected(ComponentName name) {
        keyCachingService.setMasterSecret(new Object());
        keyCachingService = null;
      }
    }, Context.BIND_AUTO_CREATE);
  }

  @Override
  public void onResume() {
    super.onResume();

    setLockTypeVisibility();

    if (TextSecurePreferences.isScreenLockEnabled(this) && !authenticated && !failure) {
      resumeScreenLock();
    }

    failure = false;
  }

  @Override
  public void onPause() {
    super.onPause();

    if (TextSecurePreferences.isScreenLockEnabled(this)) {
      pauseScreenLock();
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
  }

  @Override
  public void onActivityResult(int requestCode, int resultcode, Intent data) {
    super.onActivityResult(requestCode, resultcode, data);
    if (requestCode != 1) return;

    if (resultcode == RESULT_OK) {
      handleAuthenticated();
    } else {
      Log.w(TAG, "Authentication failed");
      failure = true;
    }
  }

  private void handleAuthenticated() {
    authenticated = true;
    //TODO Replace with a proper call.
    if (keyCachingService != null) {
      keyCachingService.setMasterSecret(new Object());
    }

    // Finish and proceed with the next intent.
    Intent nextIntent = getIntent().getParcelableExtra("next_intent");
    if (nextIntent != null) {
      try {
        startActivity(nextIntent);
      } catch (java.lang.SecurityException e) {
        Log.w(TAG, "Access permission not passed from PassphraseActivity, retry sharing.", e);
      }
    }
    finish();
  }

  private void initializeResources() {

    TextView statusTitle = findViewById(R.id.app_lock_status_title);
    if (statusTitle != null) {
      Context c = getApplicationContext();
      String lockedTxt = Phrase.from(c, R.string.lockAppLocked)
              .put(APP_NAME_KEY, c.getString(R.string.app_name))
              .format().toString();
      statusTitle.setText(lockedTxt);
    }

    visibilityToggle              = findViewById(R.id.button_toggle);
    fingerprintPrompt             = findViewById(R.id.fingerprint_auth_container);
    lockScreenButton              = findViewById(R.id.lock_screen_auth_container);
    fingerprintManager            = FingerprintManagerCompat.from(this);
    fingerprintCancellationSignal = new CancellationSignal();
    fingerprintListener           = new FingerprintListener();

    fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp);
    fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN);

    lockScreenButton.setOnClickListener(v -> resumeScreenLock());
  }

  private void setLockTypeVisibility() {
    if (TextSecurePreferences.isScreenLockEnabled(this)) {
      if (fingerprintManager.isHardwareDetected() && fingerprintManager.hasEnrolledFingerprints()) {
        fingerprintPrompt.setVisibility(View.VISIBLE);
        lockScreenButton.setVisibility(View.GONE);
      } else {
        fingerprintPrompt.setVisibility(View.GONE);
        lockScreenButton.setVisibility(View.VISIBLE);
      }
    } else {
      fingerprintPrompt.setVisibility(View.GONE);
      lockScreenButton.setVisibility(View.GONE);
    }
  }

  private void resumeScreenLock() {
    KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

    assert keyguardManager != null;

    if (!keyguardManager.isKeyguardSecure()) {
      Log.w(TAG ,"Keyguard not secure...");
      TextSecurePreferences.setScreenLockEnabled(getApplicationContext(), false);
      TextSecurePreferences.setScreenLockTimeout(getApplicationContext(), 0);
      handleAuthenticated();
      return;
    }

    if (fingerprintManager.isHardwareDetected() && fingerprintManager.hasEnrolledFingerprints()) {
      Log.i(TAG, "Listening for fingerprints...");
      fingerprintCancellationSignal = new CancellationSignal();
      Signature signature;
      try {
        signature = biometricSecretProvider.getOrCreateBiometricSignature(this);
        hasSignatureObject = true;
      } catch (Exception e) {
        signature = null;
        hasSignatureObject = false;
        Log.e(TAG, "Error getting / creating signature", e);
      }
      fingerprintManager.authenticate(
              signature == null ? null : new FingerprintManagerCompat.CryptoObject(signature),
              0,
              fingerprintCancellationSignal,
              fingerprintListener,
              null
      );
    } else {
      Log.i(TAG, "firing intent...");
      Intent intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock Session", "");
      startActivityForResult(intent, 1);
    }
  }

  private void pauseScreenLock() {
    if (fingerprintCancellationSignal != null) {
      fingerprintCancellationSignal.cancel();
    }
  }

  private class FingerprintListener extends FingerprintManagerCompat.AuthenticationCallback {
    @Override
    public void onAuthenticationError(int errMsgId, CharSequence errString) {
      Log.w(TAG, "Authentication error: " + errMsgId + " " + errString);
      onAuthenticationFailed();
    }

    @Override
    public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {
      Log.i(TAG, "onAuthenticationSucceeded");
      if (result.getCryptoObject() == null || result.getCryptoObject().getSignature() == null) {
        if (hasSignatureObject) {
          // authentication failed
          onAuthenticationFailed();
        } else {
          fingerprintPrompt.setImageResource(R.drawable.ic_check_white_48dp);
          fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.green_500), PorterDuff.Mode.SRC_IN);
          fingerprintPrompt.animate().setInterpolator(new BounceInterpolator()).scaleX(1.1f).scaleY(1.1f).setDuration(500).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
              handleAuthenticated();

              fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp);
              fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN);
            }
          }).start();
        }
        return;
      }
      // Signature object now successfully unlocked
      boolean authenticationSucceeded = false;
      try {
        Signature signature = result.getCryptoObject().getSignature();
        byte[] random = biometricSecretProvider.getRandomData();
        signature.update(random);
        byte[] signed = signature.sign();
        authenticationSucceeded = biometricSecretProvider.verifySignature(random, signed);
      } catch (Exception e) {
        Log.e(TAG, "onAuthentication signature generation and verification failed", e);
      }
      if (!authenticationSucceeded) {
        onAuthenticationFailed();
        return;
      }

      fingerprintPrompt.setImageResource(R.drawable.ic_check_white_48dp);
      fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.green_500), PorterDuff.Mode.SRC_IN);
      fingerprintPrompt.animate().setInterpolator(new BounceInterpolator()).scaleX(1.1f).scaleY(1.1f).setDuration(500).setListener(new AnimationCompleteListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
          handleAuthenticated();

          fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp);
          fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN);
        }
      }).start();
    }

    @Override
    public void onAuthenticationFailed() {
      Log.w(TAG, "onAuthenticationFailed()");

      fingerprintPrompt.setImageResource(R.drawable.ic_close_white_48dp);
      fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.red_500), PorterDuff.Mode.SRC_IN);

      TranslateAnimation shake = new TranslateAnimation(0, 30, 0, 0);
      shake.setDuration(50);
      shake.setRepeatCount(7);
      shake.setAnimationListener(new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {}

        @Override
        public void onAnimationEnd(Animation animation) {
          fingerprintPrompt.setImageResource(R.drawable.ic_fingerprint_white_48dp);
          fingerprintPrompt.getBackground().setColorFilter(getResources().getColor(R.color.signal_primary), PorterDuff.Mode.SRC_IN);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {}
      });

      fingerprintPrompt.startAnimation(shake);
    }
  }
}