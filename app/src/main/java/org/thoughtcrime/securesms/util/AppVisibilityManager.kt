package org.thoughtcrime.securesms.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVisibilityManager @Inject constructor(
    scope: CoroutineScope
) {
    val isAppVisible: StateFlow<Boolean> = ProcessLifecycleOwner
        .get()
        .lifecycle
        .currentStateFlow
        .mapStateFlow(scope) { it.isAtLeast(Lifecycle.State.STARTED) }
}
