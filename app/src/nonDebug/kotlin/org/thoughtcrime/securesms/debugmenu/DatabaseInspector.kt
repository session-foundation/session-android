package org.thoughtcrime.securesms.debugmenu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseInspector @Inject constructor() {
    val available: Boolean get() = false

    val enabled: StateFlow<Boolean> = MutableStateFlow(false)

    fun start() {
    }

    fun stop() {
    }
}