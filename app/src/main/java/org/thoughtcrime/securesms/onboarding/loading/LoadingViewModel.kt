package org.thoughtcrime.securesms.onboarding.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class State {
    LOADING,
    SUCCESS,
    FAIL
}

private val ANIMATE_TO_DONE_TIME = 500.milliseconds
private val IDLE_DONE_TIME = 1.seconds
private val TIMEOUT_TIME = 15.seconds

private val REFRESH_TIME = 50.milliseconds

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class LoadingViewModel @Inject constructor(
    val prefs: TextSecurePreferences,
    val configFactory: ConfigFactoryProtocol,
): ViewModel() {

    private val state = MutableStateFlow(State.LOADING)

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            state.flatMapLatest {
                when (it) {
                    State.LOADING -> progress(0f, 1f, TIMEOUT_TIME)
                    else -> progress(progress.value, 1f, ANIMATE_TO_DONE_TIME)
                }
            }.buffer(0, BufferOverflow.DROP_OLDEST)
                .collectLatest { _progress.value = it }
        }

        viewModelScope.launch {
            try {
                (configFactory.configUpdateNotifications
                    .filterIsInstance<ConfigUpdateNotification.UserConfigsMerged>() as Flow<*>)
                    .onStart { emit(Unit) }
                    .filter {
                        prefs.getLocalNumber() != null &&
                                configFactory.withUserConfigs { configs ->
                            !configs.userProfile.getName().isNullOrEmpty()
                        }
                    }
                    .timeout(TIMEOUT_TIME)
                    .first()
                onSuccess()
            } catch (e: Exception) {
                Log.d("LoadingViewModel", "Failed to load user configs", e)
                onFail()
            }
        }
    }

    private suspend fun onSuccess() {
        state.value = State.SUCCESS
        delay(IDLE_DONE_TIME)
        _events.emit(Event.SUCCESS)
}

    private suspend fun onFail() {
        state.value = State.FAIL
        delay(IDLE_DONE_TIME)
        _events.emit(Event.TIMEOUT)
    }
}

sealed interface Event {
    object SUCCESS: Event
    object TIMEOUT: Event
}

private fun progress(
    init: Float,
    target: Float,
    time: Duration,
    refreshRate: Duration = REFRESH_TIME
): Flow<Float> = flow {
    val startMs = System.currentTimeMillis()
    val timeMs = time.inWholeMilliseconds
    val finishMs = startMs + timeMs
    val range = target - init

    generateSequence { System.currentTimeMillis() }.takeWhile { it < finishMs }.forEach {
        emit((it - startMs) * range / timeMs + init)
        delay(refreshRate)
    }

    emit(target)
}
