package org.thoughtcrime.securesms.preferences

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatsPreferenceViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory
) : ViewModel() {

}