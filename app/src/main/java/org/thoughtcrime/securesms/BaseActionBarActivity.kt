package org.thoughtcrime.securesms

import android.app.ActivityManager.TaskDescription
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.os.Build.VERSION
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.conversation.v2.WindowUtil
import org.thoughtcrime.securesms.util.ThemeState
import org.thoughtcrime.securesms.util.UiModeUtilities.isDayUiMode
import org.thoughtcrime.securesms.util.themeState

abstract class BaseActionBarActivity : AppCompatActivity() {
    var currentThemeState: ThemeState? = null

    private var modifiedTheme: Resources.Theme? = null

    private val preferences: TextSecurePreferences
        get() {
            val appContext =
                applicationContext as ApplicationContext
            return appContext.textSecurePreferences
        }

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
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)


        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setHomeButtonEnabled(true)
        }

        // Apply insets to your views
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
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

        // apply lightStatusBar manually as API 26 does not update properly via applyTheme
        // https://issuetracker.google.com/issues/65883460?pli=1
        if (VERSION.SDK_INT >= 26 && VERSION.SDK_INT <= 27) WindowUtil.setLightStatusBarFromTheme(
            this
        )
        if (VERSION.SDK_INT == 27) WindowUtil.setLightNavigationBarFromTheme(this)
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
