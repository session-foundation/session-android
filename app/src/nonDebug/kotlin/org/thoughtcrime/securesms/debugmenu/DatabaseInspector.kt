package org.thoughtcrime.securesms.debugmenu

import dev.fanchao.sqliteviewer.model.SupportQueryable
import dev.fanchao.sqliteviewer.startDatabaseViewerServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DatabaseInspector {
    val available: Boolean get() = false

    val enabled: StateFlow<Boolean> = MutableStateFlow(false)

    fun start() {
    }

    fun stop() {
    }
}