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

    val deprecatedTime: StateFlow<ZonedDateTime> get() = mutableDeprecatedTime

    // The time a warning will be shown to users that legacy groups are being deprecated.
    private val defaultDeprecatingStartTime = ZonedDateTime.of(2025, 6, 23, 0, 0, 0, 0, ZoneId.of("UTC"))

    private val mutableDeprecatingStartTime: MutableStateFlow<ZonedDateTime> = MutableStateFlow(
        prefs.deprecatingStartTimeOverride ?: defaultDeprecatingStartTime
    )

    val deprecatingStartTime: StateFlow<ZonedDateTime> get() = mutableDeprecatingStartTime

    @Suppress("OPT_IN_USAGE")
    val deprecationState: StateFlow<DeprecationState>
        get() = combine(mutableDeprecationStateOverride,
            mutableDeprecatedTime,
            mutableDeprecatingStartTime,
            ::Triple
        ).flatMapLatest { (overriding, deprecatedTime, deprecatingStartTime) ->
            if (overriding != null) {
                flowOf(overriding)
            } else {
                flow {
                    val now = ZonedDateTime.now()

                    if (now.isBefore(deprecatingStartTime)) {
                        emit(DeprecationState.NOT_DEPRECATING)
                        delay(Duration.between(now, deprecatingStartTime).toMillis())
                    }

                    if (now.isBefore(deprecatedTime)) {
                        emit(DeprecationState.DEPRECATING)
                        delay(Duration.between(now, deprecatedTime).toMillis())
                    }

                    emit(DeprecationState.DEPRECATED)
                }
            }
        }.stateIn(
            scope = GlobalScope,
            started = SharingStarted.Lazily,
            initialValue = mutableDeprecationStateOverride.value ?: DeprecationState.NOT_DEPRECATING
        )

    fun overrideDeprecationState(deprecationState: DeprecationState?) {
        mutableDeprecationStateOverride.value = deprecationState
        prefs.deprecationStateOverride = deprecationState?.name
    }

    fun overrideDeprecatedTime(deprecatedTime: ZonedDateTime?) {
        mutableDeprecatedTime.value = deprecatedTime ?: defaultDeprecatedTime
        prefs.deprecatedTimeOverride = deprecatedTime
    }

    fun overrideDeprecatingStartTime(deprecatingStartTime: ZonedDateTime?) {
        mutableDeprecatingStartTime.value = deprecatingStartTime ?: defaultDeprecatingStartTime
        prefs.deprecatingStartTimeOverride = deprecatingStartTime
    }

    enum class DeprecationState {
        NOT_DEPRECATING,
        DEPRECATING,
        DEPRECATED
    }
}
