/*
 * Copyright (C) 2014-2017 Open Whisper Systems
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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

// An activity to quickly share content with contacts.
@AndroidEntryPoint
class ShareActivity : FullComposeScreenLockActivity() {

    private val viewModel: ShareViewModel by viewModels()

    companion object {
        const val EXTRA_THREAD_ID          = "thread_id"
        const val EXTRA_ADDRESS_MARSHALLED = "address_marshalled"
        const val EXTRA_DISTRIBUTION_TYPE  = "distribution_type"
    }


    @Composable
    override fun ComposeContent() {
        ShareScreen(
            viewModel = viewModel,
            onBack = { finish() },
        )
    }

    override fun onCreate(icicle: Bundle?, ready: Boolean) {
        super.onCreate(icicle, ready)

        initializeMedia()

        lifecycleScope.launch {
            viewModel.uiEvents.collect {
                when (it) {
                    is ShareViewModel.ShareUIEvent.GoToScreen -> {
                        startActivity(it.intent)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initializeMedia()
    }

    public override fun onPause() {
        super.onPause()
        if (viewModel.onPause()) {
            if (!isFinishing) { finish() }
        }
    }

    private fun initializeMedia() {
        val streamExtra = intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM)
        var charSequenceExtra: CharSequence? = null
        try {
            charSequenceExtra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }
        catch (e: Exception) {
            // It's not necessarily an issue if there's no text extra when sharing files - but we do
            // have to catch any failed attempt.
        }

        viewModel.initialiseMedia(streamExtra, charSequenceExtra, intent)
    }
}