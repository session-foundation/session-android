package org.thoughtcrime.securesms.media

import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeActivity
import javax.inject.Inject

@AndroidEntryPoint
class MediaOverviewActivity : FullComposeActivity() {
    @Inject
    lateinit var viewModelFactory: MediaOverviewViewModel.AssistedFactory

    private val viewModel: MediaOverviewViewModel by viewModels {
        viewModelFactory.create(IntentCompat.getParcelableExtra(intent, EXTRA_ADDRESS, Address::class.java)!!)
    }

    @Composable
    override fun ComposeContent() {
        MediaOverviewScreen(viewModel, onClose = this::finish)
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"

        @JvmStatic
        fun createIntent(context: Context, address: Address): Intent {
            return Intent(context, MediaOverviewActivity::class.java).apply {
                putExtra(EXTRA_ADDRESS, address)
            }
        }
    }
}