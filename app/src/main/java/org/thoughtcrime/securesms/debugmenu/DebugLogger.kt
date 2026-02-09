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
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryGreen
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.util.DateUtils
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_LOG_ENTRIES = 200

/**
 * A class that keeps track of certain logs and allows certain logs to pop as toasts
 * To use: Set the tag as one of the known [DebugLogGroup]
 */
@Singleton
class DebugLogger @Inject constructor(
    private val app: Application,
    private val prefs: TextSecurePreferences,
    private val dateUtils: DateUtils,
    @ManagerScope private val scope: CoroutineScope
) : Log.Logger() {
    private val prefPrefix: String = "debug_logger_"

    private val buffer = ArrayDeque<DebugLogData>(MAX_LOG_ENTRIES)

    private val logChanges = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val allowedTags: Set<String> =
        DebugLogGroup.entries.map { it.label.lowercase() }.toSet()

    private fun groupForTag(tag: String): DebugLogGroup? =
        DebugLogGroup.entries.firstOrNull { it.label.equals(tag, ignoreCase = true) }

    val logSnapshots: Flow<List<DebugLogData>>
        get() = logChanges.onStart { emit(Unit) }.map { currentSnapshot() }

    // In-memory cache for toast prefs
    private val toastEnabled = java.util.EnumMap<DebugLogGroup, Boolean>(DebugLogGroup::class.java).apply {
        DebugLogGroup.entries.forEach { group ->
            this[group] = prefs.getBooleanPreference(prefPrefix + group.label, false)
        }
    }

    fun currentSnapshot(): List<DebugLogData> =
        synchronized(buffer) { buffer.toList().asReversed() }

    fun clearAll() {
        synchronized(buffer) { buffer.clear() }
        logChanges.tryEmit(Unit)
    }

    fun showGroupToast(group: DebugLogGroup, showToast: Boolean) {
        toastEnabled[group] = showToast
        prefs.setBooleanPreference(prefPrefix + group.label, showToast)
    }

    fun getGroupToastPreference(group: DebugLogGroup): Boolean =
        toastEnabled[group] == true

    // ---- Log.Logger overrides (no “level” logic) ----
    override fun v(tag: String, message: String?, t: Throwable?) = add(tag, message, t)
    override fun d(tag: String, message: String?, t: Throwable?) = add(tag, message, t)
    override fun i(tag: String, message: String?, t: Throwable?) = add(tag, message, t)
    override fun w(tag: String, message: String?, t: Throwable?) = add(tag, message, t)
    override fun e(tag: String, message: String?, t: Throwable?) = add(tag, message, t)
    override fun wtf(tag: String, message: String?, t: Throwable?) = add(tag, message, t)
    override fun blockUntilAllWritesFinished() { /* no-op */ }

    private fun add(tag: String, message: String?, t: Throwable?) {
        // Capture ONLY if tag is in our allow-list
        if (!allowedTags.contains(tag.lowercase())) return

        val group = groupForTag(tag) ?: return

        val now = Instant.now()
        val text = when {
            !message.isNullOrBlank() -> message
            t != null -> t.localizedMessage ?: t::class.java.simpleName
            else -> "" // nothing meaningful
        }

        val entry = DebugLogData(
            message = text,
            group = group,
            date = now,
        )

        synchronized(buffer) {
            if (buffer.size == MAX_LOG_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
        logChanges.tryEmit(Unit)

        // Toast decision is independent from capture.
        if (toastEnabled[group] == true) {
            scope.launch(Dispatchers.Main) {
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

data class DebugLogData(
    val message: String,
    val group: DebugLogGroup,
    val date: Instant,
)

enum class DebugLogGroup(val label: String, val color: Color){
    AVATAR("Avatar", primaryOrange),
    PRO_SUBSCRIPTION("ProSubscription", primaryGreen),
    PRO_DATA("ProData", primaryBlue)
}