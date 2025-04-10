package org.thoughtcrime.securesms

import android.app.ActivityManager.TaskDescription
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StyleRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewGroupCompat
import androidx.core.view.WindowCompat
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.ThemeUtil
import org.thoughtcrime.securesms.util.ThemeState
import org.thoughtcrime.securesms.util.UiModeUtilities.isDayUiMode
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.themeState

private val DefaultLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DefaultDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

abstract class BaseActionBarActivity : AppCompatActivity() {
    var currentThemeState: ThemeState? = null

    private var modifiedTheme: Resources.Theme? = null

    private val preferences: TextSecurePreferences
        get() {
            val appContext =
                applicationContext as ApplicationContext
            return appContext.textSecurePreferences
        }

    // Whether to apply default window insets to the decor view
    open val applyDefaultWindowInsets: Boolean
        get() = true

    @get:StyleRes
    private val desiredTheme: Int
        get() {
            val themeState = preferences.themeState()
            val userSelectedTheme = themeState.theme

            // If the user has configured Session to follow the system light/dark theme mode then do so..
            if (themeState.followSystem) {
                // Use light or dark versions of the user's theme based on light-mode / dark-mode settings

                val isDayUi = isDayUiMode(this)
                return if (userSelectedTheme == R.style.Ocean_Dark || userSelectedTheme == R.style.Ocean_Light) {
                    if (isDayUi) R.style.Ocean_Light else R.style.Ocean_Dark
                } else {
                    if (isDayUi) R.style.Classic_Light else R.style.Classic_Dark
                }
            } else  // ..otherwise just return their selected theme.
            {
                return userSelectedTheme
            }
        }

    @get:StyleRes
    private val accentTheme: Int?
        get() {
            if (!preferences.hasPreference(TextSecurePreferences.SELECTED_ACCENT_COLOR)) return null
            val themeState = preferences.themeState()
            return themeState.accentStyle
        }

    override fun getTheme(): Resources.Theme {
        if (modifiedTheme != null) {
            return modifiedTheme!!
        }

        // New themes
        modifiedTheme = super.getTheme()
        modifiedTheme!!.applyStyle(desiredTheme, true)
        val accentTheme = accentTheme
        if (accentTheme != null) {
            modifiedTheme!!.applyStyle(accentTheme, true)
        }
        currentThemeState = preferences.themeState()
        return modifiedTheme!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val detectDarkMode = { _: Resources ->
            ThemeUtil.isDarkTheme(this)
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, detectDarkMode),
            navigationBarStyle = SystemBarStyle.auto(DefaultLightScrim, DefaultDarkScrim, detectDarkMode)
        )
        super.onCreate(savedInstanceState)


        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setHomeButtonEnabled(true)
        }


        // Apply a fix that views not getting inset dispatch when the inset was consumed by siblings on API <= 29
        // See: https://developer.android.com/develop/ui/views/layout/edge-to-edge#backward-compatible-dispatching
        ViewGroupCompat.installCompatInsetsDispatch(window.decorView)

        if (applyDefaultWindowInsets) {
            findViewById<View>(android.R.id.content)?.applySafeInsetsPaddings()
        }
    }

    override fun onResume() {
        super.onResume()

        initializeScreenshotSecurity(true)
        val name = resources.getString(R.string.app_name)
        val icon = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_foreground)
        val color = resources.getColor(R.color.app_icon_background)
        setTaskDescription(TaskDescription(name, icon, color))
        if (currentThemeState != preferences.themeState()) {
            recreate()
        }

    }

    override fun onPause() {
        super.onPause()
        initializeScreenshotSecurity(false)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (super.onSupportNavigateUp()) return true

        onBackPressed()
        return true
    }

    private fun initializeScreenshotSecurity(isResume: Boolean) {
        if (!isResume) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        private val TAG: String = BaseActionBarActivity::class.java.simpleName
    }
}
