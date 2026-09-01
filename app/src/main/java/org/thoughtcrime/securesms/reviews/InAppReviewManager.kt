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
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

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
        // UNLIMITED, not the default rendezvous. On a rendezvous channel `send` does not complete when
        // it is called — it suspends until this collector receives — so an event emitted from a screen's
        // scope is lost if that screen goes away in between. Buffering makes the handoff finish at the
        // call, which is what lets [onEvent] be non-suspending.
        val channel = Channel<Event>(capacity = Channel.UNLIMITED)
        eventsChannel = channel

        scope.launch {
            val startState = prefs.reviewState ?: run {
                if (storeReviewManager.supportsReviewFlow) {
                    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                    InAppReviewState.WaitingForTrigger(
                        // The QA override comes first, and only exists because the real answer is not
                        // reachable from a test: a harness installs over an existing package, so
                        // firstInstallTime and lastUpdateTime always differ and the fresh-install branch —
                        // the one that allows the path and theme triggers — can never be exercised.
                        // Null in any build without the launch config, so the real answer stands.
                        appUpdated = prefs.getDebugAppUpdated()
                            ?: (pkg.firstInstallTime != pkg.lastUpdateTime)
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

    /**
     * Record something the user did that might earn a review prompt.
     *
     * Deliberately NOT suspending, and deliberately not requiring a coroutine at the call site. Every
     * caller is a screen, and a screen's scope dies when the user leaves it — which is exactly when these
     * events happen. Changing the theme and pressing back immediately used to lose the event entirely,
     * because the emission had to outlive the screen that triggered it.
     *
     * The channel is UNLIMITED, so this hands off and returns rather than waiting for the collector.
     */
    fun onEvent(event: Event) {
        val result = eventsChannel.trySend(event)

        // Unreachable on an unlimited channel short of the manager being closed, but silence here would
        // look exactly like the bug this replaced: a trigger that simply never arrives.
        if (result.isFailure) {
            Log.w(TAG, "Dropped review event $event: ${result.exceptionOrNull()}")
        }
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