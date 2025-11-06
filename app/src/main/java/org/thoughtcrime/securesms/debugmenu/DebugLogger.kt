package org.thoughtcrime.securesms.debugmenu

import android.app.Application
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.util.DateUtils
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class that keeps track of certain logs and allows certain logs to pop as toasts
 */
@Singleton
class DebugLogger @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    private val dateUtils: DateUtils
){
    private val prefPrefix: String = "debug_logger_"

    private val _logs: MutableStateFlow<List<DebugLogData>> = MutableStateFlow(emptyList())
    val logs: StateFlow<List<DebugLogData>> = _logs

    fun showGroupToast(group: DebugLogGroup, showToast: Boolean){
        prefs.setBooleanPreference(prefPrefix + group.label, showToast)
    }

    fun getGroupToastPreference(group: DebugLogGroup): Boolean{
        return prefs.getBooleanPreference(prefPrefix + group.label, false)
    }

    fun log(message: String, group: DebugLogGroup, tag: String = "", logSeverity: LogSeverity = LogSeverity.INFO, throwable: Throwable? = null){
        // add this message to our list
        val date = Instant.now()
        _logs.update {
            (it + DebugLogData(
                message = message,
                group = group,
                date = date,
                formattedDate = dateUtils.getLocaleFormattedTime(date.toEpochMilli())
            )).sortedByDescending { log -> log.date }
        }

        // log the message
        when(logSeverity){
            LogSeverity.INFO -> Log.d(tag, message, throwable)
            LogSeverity.WARNING -> Log.w(tag, message, throwable)
            LogSeverity.ERROR -> Log.e(tag, message, throwable)
        }

        // show this as a toast if the prefs have this group toggled
        if(prefs.getBooleanPreference(prefPrefix + group.label, false)){
            Toast.makeText(application, message, Toast.LENGTH_LONG).show()
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

    fun clearAllLogs(){
        _logs.update { emptyList() }
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