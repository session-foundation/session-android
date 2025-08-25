package org.thoughtcrime.securesms.debugmenu

import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeActivity

@AndroidEntryPoint
class DebugActivity : FullComposeActivity() {

    @Composable
    override fun ComposeContent() {
        DebugMenuScreen(
            onClose = { finish() }
        )
    }
}