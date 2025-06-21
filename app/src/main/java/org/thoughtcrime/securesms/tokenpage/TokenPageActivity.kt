package org.thoughtcrime.securesms.tokenpage

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.FullComposeActivity
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.ui.setComposeContent

@AndroidEntryPoint
class TokenPageActivity : FullComposeScreenLockActivity() {
    private val viewModel: TokenPageViewModel by viewModels()

    @Composable
    override fun ComposeContent() {
        TokenPageScreen(
            tokenPageViewModel = viewModel,
            onClose = { finish() }
        )
    }
}