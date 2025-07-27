package org.thoughtcrime.securesms.reviews

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
@Singleton
class InAppReviewManager @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val prefs: TextSecurePreferences,
    private val json: Json,
    private val storeReviewManager: StoreReviewManager,
    @param:ManagerScope private val scope: CoroutineScope,
) {
    private val stateChangeNotification = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val eventsChannel: SendChannel<Event>

    @Suppress("OPT_IN_USAGE")
    val shouldShowPrompt: StateFlow<Boolean> = stateChangeNotification
        .onStart { emit(Unit) }
        .map { prefs.reviewState }
        .flatMapLatest { state ->
            when (state) {
                InAppReviewState.DismissedForever, is InAppReviewState.WaitingForTrigger, null -> flowOf(false)
                InAppReviewState.ShowingReviewRequest -> flowOf(true)
                is InAppReviewState.DismissedUntil -> {
                    val now = System.currentTimeMillis()
                    val delayMills = state.untilTimestampMills - now
                    if (delayMills <= 0) {
                        flowOf(true)
                    } else {
                        flow {
                            emit(false)
                            Log.i(TAG, "Review request is not ready yet, will show in $delayMills ms.")
                            delay(delayMills)
                            emit(true)
                        }
                    }
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        val channel = Channel<Event>()
        eventsChannel = channel

        scope.launch {
            val startState = prefs.reviewState ?: run {
                if (storeReviewManager.supportsReviewFlow) {
                    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                    InAppReviewState.WaitingForTrigger(
                        appUpdated = pkg.firstInstallTime != pkg.lastUpdateTime
                    )
                } else {
                    InAppReviewState.DismissedForever
                }
            }

            channel.consumeAsFlow()
                .scan(startState) { state, event ->
                    Log.d(TAG, "Received event: $event, current state: $state")
                    when {
                        // If we have determined that we should not show the review request,
                        // no amount of events will change that.
                        state == InAppReviewState.DismissedForever -> state

                        // If we have shown the review request and the user has abandoned it...
                        state == InAppReviewState.ShowingReviewRequest && event == Event.ReviewFlowAbandoned -> {
                            InAppReviewState.DismissedUntil(System.currentTimeMillis() + REVIEW_REQUEST_DISMISS_DELAY.inWholeMilliseconds)
                        }

                        // If the user abandoned the review flow **again**...
                        state is InAppReviewState.DismissedUntil && event == Event.ReviewFlowAbandoned -> {
                            InAppReviewState.DismissedForever
                        }

                        // If we are showing the review request and the user has dismissed it...
                        state == InAppReviewState.ShowingReviewRequest && event == Event.Dismiss -> {
                            InAppReviewState.DismissedForever
                        }

                        // If we are showing the review request and the user has dismissed it...
                        state is InAppReviewState.DismissedUntil && event == Event.Dismiss -> {
                            InAppReviewState.DismissedForever
                        }

                        // If we are waiting for the user to trigger the review request, and eligible
                        // trigger events happen...
                        state is InAppReviewState.WaitingForTrigger && (
                                (state.appUpdated && event == Event.DonateButtonClicked) ||
                                        (!state.appUpdated && event in EnumSet.of(
                                            Event.PathScreenVisited,
                                            Event.DonateButtonClicked,
                                            Event.ThemeChanged
                                        ))
                                ) -> {
                            InAppReviewState.ShowingReviewRequest
                        }

                        else -> state
                    }
                }
                .distinctUntilChanged()
                .collectLatest {
                    prefs.reviewState = it
                    Log.d(TAG, "New review state is: $it")
                }
        }
    }

    suspend fun onEvent(event: Event) {
        eventsChannel.send(event)
    }

    enum class Event {
        PathScreenVisited,
        DonateButtonClicked,
        ThemeChanged,
        ReviewFlowAbandoned,
        Dismiss,
    }

    private var TextSecurePreferences.reviewState
        get() = prefs.inAppReviewState?.let {
            runCatching { json.decodeFromString<InAppReviewState>(it) }
                .onFailure { Log.w(TAG, "Failed to decode review state", it) }
                .getOrNull()
        }
        set(value) {
            prefs.inAppReviewState =
                value?.let { json.encodeToString(InAppReviewState.serializer(), it) }
            stateChangeNotification.tryEmit(Unit)
        }


    companion object {
        private const val TAG = "InAppReviewManager"

        @VisibleForTesting
        val REVIEW_REQUEST_DISMISS_DELAY = 14.days
    }
}