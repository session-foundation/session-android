package org.thoughtcrime.securesms.pro

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.util.castAwayType

/**
 * Creates a flow that only emits when the debug flag forcePostPro is enabled.
 */
fun <T> TextSecurePreferences.flowPostProLaunch(flowFactory: () -> Flow<T>): Flow<T> {
    @Suppress("OPT_IN_USAGE")
    return TextSecurePreferences.events
        .filter { it == TextSecurePreferences.SET_FORCE_POST_PRO }
        .castAwayType()
        .onStart { emit(Unit) }
        .filter { forcePostPro() }
        .flatMapLatest { flowFactory() }
}
