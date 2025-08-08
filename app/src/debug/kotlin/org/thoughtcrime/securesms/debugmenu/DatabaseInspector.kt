package org.thoughtcrime.securesms.debugmenu

import dev.fanchao.sqliteviewer.StartedInstance
import dev.fanchao.sqliteviewer.model.SupportQueryable
import dev.fanchao.sqliteviewer.startDatabaseViewerServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    private val instance = MutableStateFlow<StartedInstance?>(null)

    val enabled: StateFlow<Boolean> = instance
        .flatMapLatest { st ->
            st?.state?.map {
                it is StartedInstance.State.Running
            } ?: flowOf(false)
        }
        .stateIn(coroutineScope, SharingStarted.Eagerly, false)

    fun start() {
        instance.update { inst ->
            inst?.takeIf { it.state.value !is StartedInstance.State.Stopped } ?: startDatabaseViewerServer(
                currentActivityObserver.currentActivity.value!!,
                port = 3000,
                queryable = SupportQueryable(openHelper.get().writableDatabase)
            )
        }
    }

    fun stop() {
        instance.update {
            it?.stop()
            null
        }
    }
}