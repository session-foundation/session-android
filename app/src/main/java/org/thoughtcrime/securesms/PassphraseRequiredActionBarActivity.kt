package org.thoughtcrime.securesms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.TextSecurePreferences.Companion.isScreenLockEnabled
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.onboarding.landing.LandingActivity
import org.thoughtcrime.securesms.service.KeyCachingService
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
        } else {
            Log.w(TAG,  "PassphraseRequiredActionBarActivity.onCreate() received a null savedInstanceState Bundle."
            )
        }


        val locked = KeyCachingService.isLocked(this) && isScreenLockEnabled(this) && getLocalNumber(this) != null
        routeApplicationState(locked)

        //super.onCreate(savedInstanceState);
        if (!isFinishing) {
            initializeClearKeyReceiver()
            Log.w(TAG, "We aren't finishing so calling onCreate(savedInstanceState, true);")
            onCreate(savedInstanceState, true)
        }
    }

    //protected void onPreCreate() {}
    protected open fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {}

    override fun onPause() {
        Log.i(TAG, "onPause()")
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy()")
        super.onDestroy()
        removeClearKeyReceiver(this)
    }

    fun onMasterSecretCleared() {
        Log.i(TAG, "onMasterSecretCleared()")
        if (ApplicationContext.getInstance(this).isAppVisible()) routeApplicationState(true)
        else finish()
    }

    protected fun <T : Fragment?> initFragment(
        @IdRes target: Int,
        fragment: T
    ): T? {
        return initFragment<T?>(target, fragment, null)
    }

    protected fun <T : Fragment?> initFragment(
        @IdRes target: Int,
        fragment: T,
        locale: Locale?
    ): T? {
        return initFragment<T?>(target, fragment, locale, null)
    }

    protected fun <T : Fragment?> initFragment(
        @IdRes target: Int,
        fragment: T,
        locale: Locale?,
        extras: Bundle?
    ): T? {
        val args = Bundle()
        args.putSerializable(LOCALE_EXTRA, locale)

        if (extras != null) {
            args.putAll(extras)
        }

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

        when (state) {
            STATE_PROMPT_PASSPHRASE -> return getPromptPassphraseIntent()
            STATE_UPGRADE_DATABASE -> return getUpgradeDatabaseIntent()
            STATE_WELCOME_SCREEN -> return getWelcomeIntent()
            else -> return null
        }
    }

    private fun getApplicationState(locked: Boolean): Int {
        if (getLocalNumber(this) == null) {
            return STATE_WELCOME_SCREEN
        } else if (locked) {
            return STATE_PROMPT_PASSPHRASE
        } else if (DatabaseUpgradeActivity.isUpdate(this)) {
            return STATE_UPGRADE_DATABASE
        } else {
            return STATE_NORMAL
        }
    }

    private fun getPromptPassphraseIntent(): Intent {
        val i = intent
        val b = i.extras
        if (b != null) {
            for (key in b.keySet()) {
                Log.w(TAG, "Key: " + key + " --> " + b.get(key))
            }
        }

        // If this is an attempt to externally share something while the app is locked then we need
        // to write the intent to our own local storage - otherwise the ephemeral permission expires.
        // Note: We CANNOT just add `Intent.FLAG_GRANT_READ_URI_PERMISSION` to this intent as we
        // pass it around because we don't have permission to do that (i.e., it doesn't work).
        Log.w(TAG, "Action is: " + i.action)
        if (i.action === "android.intent.action.SEND") {
            Log.w(TAG, "Need to rewrite the intent here!")
        }

        //Intent i = getIntent();
        i.flags = i.flags or Intent.FLAG_GRANT_READ_URI_PERMISSION

        return getRoutedIntent(PassphrasePromptActivity::class.java, i)
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
        intent.flags = intent.flags or Intent.FLAG_GRANT_READ_URI_PERMISSION

        if (nextIntent != null) {
            nextIntent.flags = nextIntent.flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
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
