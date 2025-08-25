package org.thoughtcrime.securesms

import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import org.thoughtcrime.securesms.ui.setComposeContent

/**
 * Base class for activities that use Compose UI for their full content.
 *
 * It fine-tunes options so that Compose can take over the entire screen.
 *
 * Note: you should use [FullComposeScreenLockActivity] by default, who handles the authentication
 * and routing logic. This class is only for activities that do not need these logic which should
 * be rare.
 */
abstract class FullComposeActivity : BaseActionBarActivity() {
    @Composable
    abstract fun ComposeContent()

    final override val applyDefaultWindowInsets: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCommonPropertiesForCompose()

        super.onCreate(savedInstanceState)

        setComposeContent {
            ComposeContent()
        }
    }

    companion object {
        /**
         * Apply some common properties for activities that display compose as full content.
         */
        fun AppCompatActivity.applyCommonPropertiesForCompose() {
            // Disable action bar for compose
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)

            // Deprecated note: this flag is set for older devices that do not support IME insets
            // For recent Android versions this simply doesn't work and you have to do the IME insets
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
