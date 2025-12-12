package org.thoughtcrime.securesms.conversation.v2.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.thoughtcrime.securesms.ui.UINavigator
import javax.inject.Inject

@HiltViewModel
class ConversationSettingsNavigatorHolder @Inject constructor() : ViewModel() {
    val navigator = UINavigator<ConversationSettingsDestination>()
}