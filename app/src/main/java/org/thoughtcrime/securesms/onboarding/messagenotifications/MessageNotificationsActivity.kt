package org.thoughtcrime.securesms.onboarding.messagenotifications

import android.app.Activity
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.home.startHomeActivity
import org.thoughtcrime.securesms.onboarding.loading.LoadingActivity
import org.thoughtcrime.securesms.onboarding.messagenotifications.MessageNotificationsActivity.Companion.EXTRA_PROFILE_NAME
import org.thoughtcrime.securesms.ui.setComposeContent
import org.thoughtcrime.securesms.util.setUpActionBarSessionLogo
import org.thoughtcrime.securesms.util.start
import javax.inject.Inject

@AndroidEntryPoint
class MessageNotificationsActivity : BaseActionBarActivity() {

    companion object {
        const val EXTRA_PROFILE_NAME = "EXTRA_PROFILE_NAME"
    }

    @Inject
    internal lateinit var viewModelFactory: MessageNotificationsViewModel.AssistedFactory

    val profileName by lazy { intent.getStringExtra(EXTRA_PROFILE_NAME) }

    private val viewModel: MessageNotificationsViewModel by viewModels {
        viewModelFactory.create(profileName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpActionBarSessionLogo()

        setComposeContent { MessageNotificationsScreen() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.onBackPressed()) return
                finish()
            }
        })

        lifecycleScope.launch {
            viewModel.events.collect {
                when (it) {
                    Event.Loading -> start<LoadingActivity>()
                    Event.OnboardingComplete -> startHomeActivity(isNewAccount = true, isFromOnboarding = true)
                }
            }
        }
    }

    @Composable
    private fun MessageNotificationsScreen() {
        val uiState by viewModel.uiStates.collectAsState()
        MessageNotificationsScreen(
            uiState,
            setEnabled = viewModel::setEnabled,
            onContinue = viewModel::onContinue,
            quit = viewModel::quit,
            dismissDialog = viewModel::dismissDialog
        )
    }
}

fun Activity.startMessageNotificationsActivity(profileName: String) {
    start<MessageNotificationsActivity> { putExtra(EXTRA_PROFILE_NAME, profileName) }
}
