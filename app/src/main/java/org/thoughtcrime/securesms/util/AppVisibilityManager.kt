package org.thoughtcrime.securesms.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVisibilityManager @Inject constructor() {
    private val mutableIsAppVisible = MutableStateFlow(false)

    init {
        // `addObserver` must be called on the main thread.
        GlobalScope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    mutableIsAppVisible.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    mutableIsAppVisible.value = false
                }
            })
        }
    }

    val isAppVisible: StateFlow<Boolean> get() = mutableIsAppVisible
}
