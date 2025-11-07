package org.thoughtcrime.securesms.debugmenu

import android.app.Application
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.util.DateUtils
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_LOG_ENTRIES = 200

/**
 * A class that keeps track of certain logs and allows certain logs to pop as toasts
 */
@Singleton
class DebugLogger @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    private val dateUtils: DateUtils,
    @ManagerScope private val scope: CoroutineScope
){
    private val prefPrefix: String = "debug_logger_"

    private val buffer = ArrayDeque<DebugLogData>(MAX_LOG_ENTRIES)

    private val logChanges = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // should only run when collected
    val logSnapshots: Flow<List<DebugLogData>> =
        logChanges
            .onStart { emit(Unit) }
            .map { currentSnapshot() }

    fun currentSnapshot(): List<DebugLogData> =
        synchronized(buffer) { buffer.toList().asReversed() }

    fun showGroupToast(group: DebugLogGroup, showToast: Boolean){
        prefs.setBooleanPreference(prefPrefix + group.label, showToast)
    }

    fun getGroupToastPreference(group: DebugLogGroup): Boolean{
        return prefs.getBooleanPreference(prefPrefix + group.label, false)
    }

    fun log(message: String, group: DebugLogGroup, tag: String = "", logSeverity: LogSeverity = LogSeverity.INFO, throwable: Throwable? = null){
        // add this message to our list
        val now = Instant.now()
        val entry = DebugLogData(
            message = message,
            group = group,
            date = now,
            formattedDate = dateUtils.getLocaleFormattedTime(now.toEpochMilli())
        )

        scope.launch(Dispatchers.Default) {
            synchronized(buffer) {
                if (buffer.size == MAX_LOG_ENTRIES) buffer.removeFirst()
                buffer.addLast(entry)
            }
            logChanges.tryEmit(Unit)
        }

        // log the message
        when(logSeverity){
            LogSeverity.INFO -> Log.d(tag, message, throwable)
            LogSeverity.WARNING -> Log.w(tag, message, throwable)
            LogSeverity.ERROR -> Log.e(tag, message, throwable)
        }

        // show this as a toast if the prefs have this group toggled
        if(prefs.getBooleanPreference(prefPrefix + group.label, false)){
            scope.launch(Dispatchers.Main) {
                Toast.makeText(application.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun logD(message: String, group: DebugLogGroup, tag: String = "", throwable: Throwable? = null){
        log(message = message, group = group, tag = tag, throwable = throwable, logSeverity = LogSeverity.INFO)
    }
    fun logW(message: String, group: DebugLogGroup, tag: String = "", throwable: Throwable? = null){
        log(message = message, group = group, tag = tag, throwable = throwable, logSeverity = LogSeverity.WARNING)
    }
    fun logE(message: String, group: DebugLogGroup, tag: String = "", throwable: Throwable? = null){
        log(message = message, group = group, tag = tag, throwable = throwable, logSeverity = LogSeverity.ERROR)
    }

    fun clearAllLogs() {
        scope.launch(Dispatchers.Default) {
            synchronized(buffer) { buffer.clear() }
            logChanges.tryEmit(Unit)
        }
    }
}

data class DebugLogData(
    val message: String,
    val group: DebugLogGroup,
    val date: Instant,
    val formattedDate: String
)

enum class LogSeverity{
    INFO, WARNING, ERROR
}

enum class DebugLogGroup(val label: String, val color: Color){
    AVATAR("Avatar", primaryOrange), PRO_SUBSCRIPTION("Pro Subscription", primaryGreen)
}