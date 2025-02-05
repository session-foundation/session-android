package org.session.libsession.messaging.groups

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.utilities.TextSecurePreferences
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class LegacyGroupDeprecationManager(private val prefs: TextSecurePreferences)  {
    private val mutableDeprecationStateOverride = MutableStateFlow(
        DeprecationState.entries.firstOrNull { it.name == prefs.deprecationStateOverride }
    )

    val deprecationStateOverride: StateFlow<DeprecationState?> get() = mutableDeprecationStateOverride

    // The time all legacy groups will cease working. This value can be overridden by a debug
    // facility.
    private val defaultDeprecatedTime = ZonedDateTime.of(2025, 7, 1, 0, 0, 0, 0, ZoneId.of("UTC"))

    private val mutableDeprecatedTime = MutableStateFlow<ZonedDateTime>(
        prefs.deprecatedTimeOverride ?: defaultDeprecatedTime
    )

    val deprecationTime: StateFlow<ZonedDateTime> get() = mutableDeprecatedTime

    @Suppress("OPT_IN_USAGE")
    val deprecationState: StateFlow<DeprecationState>
        get() = combine(mutableDeprecationStateOverride, mutableDeprecatedTime, ::Pair)
            .flatMapLatest { (overriding, deadline) ->
                if (overriding != null) {
                    flowOf(overriding)
                } else {
                    flow {
                        val now = ZonedDateTime.now()

                        if (now.isBefore(deadline)) {
                            emit(DeprecationState.DEPRECATING)
                            delay(Duration.between(now, deadline).toMillis())
                        }

                        emit(DeprecationState.DEPRECATED)
                    }
                }
            }
            .stateIn(
                scope = GlobalScope,
                started = SharingStarted.Lazily,
                initialValue = mutableDeprecationStateOverride.value ?: DeprecationState.DEPRECATING
            )

    fun overrideDeprecationState(deprecationState: DeprecationState?) {
        mutableDeprecationStateOverride.value = deprecationState
        prefs.deprecationStateOverride = deprecationState?.name
    }

    fun overrideDeprecatedTime(deprecatedTime: ZonedDateTime?) {
        mutableDeprecatedTime.value = deprecatedTime ?: defaultDeprecatedTime
        prefs.deprecatedTimeOverride = deprecatedTime
    }

    enum class DeprecationState {
        DEPRECATING,
        DEPRECATED
    }
}
