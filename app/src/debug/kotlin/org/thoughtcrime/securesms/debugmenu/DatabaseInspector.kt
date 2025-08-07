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
class DatabaseInspector @Inject constructor(
    @param:ManagerScope private val coroutineScope: CoroutineScope,
    private val currentActivityObserver: CurrentActivityObserver,
    private val openHelper: Provider<SQLCipherOpenHelper>,
) {
    val available: Boolean get() = true

    private val state = MutableStateFlow<Job?>(null)

    val enabled: StateFlow<Boolean> = state.map { it != null }
        .stateIn(coroutineScope, SharingStarted.Eagerly, state.value != null)

    fun start() {
        state.update { job ->
            if (job == null) {
                startDatabaseViewerServer(
                    currentActivityObserver.currentActivity.value!!,
                    scope = coroutineScope,
                    port = 3000,
                    queryable = SupportQueryable(openHelper.get().writableDatabase)
                )
            } else {
                null
            }
        }
    }

    fun stop() {
        state.update { job ->
            job?.cancel()
            null
        }
    }
}