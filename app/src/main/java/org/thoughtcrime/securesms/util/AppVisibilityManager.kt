package org.thoughtcrime.securesms.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVisibilityManager @Inject constructor() {
    private val mutableIsAppVisible = MutableStateFlow(false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                mutableIsAppVisible.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                mutableIsAppVisible.value = false
            }
        })
    }

    val isAppVisible: StateFlow<Boolean> get() = mutableIsAppVisible
}
