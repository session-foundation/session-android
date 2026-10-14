package org.session.libsession.messaging.sending_receiving

import androidx.annotation.StringRes
import network.loki.messenger.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account-level banner for unsupported messages that can't be shown in any conversation.
 *
 * It deliberately carries no count and makes no claim about senders: a stranger can deposit
 * newer-format data in our one-to-one namespace, so the banner has to stay true when they do.
 */
object UnsupportedMessageBanner {
    /**
     * After a dismissal the banner only reappears for a trigger at least this long afterwards,
     * which bounds how often a stranger can bring it back.
     */
    const val REAPPEAR_AFTER_DISMISSAL_MS: Long = 7L * 24 * 60 * 60 * 1000

    enum class Trigger {
        NEWER_FORMAT,

        /** An unknown type from one of our own devices that couldn't be placed in a conversation */
        OTHER_DEVICE,
    }

    enum class State(@StringRes val textRes: Int?) {
        HIDDEN(null),
        GENERAL(R.string.messageUnsupportedBanner),
        OTHER_DEVICE(R.string.messageUnsupportedBannerLinkedDevice),
    }

    fun state(triggeredAtMs: Long?, otherDeviceTriggeredAtMs: Long?, dismissedAtMs: Long?): State {
        if (triggeredAtMs == null) return State.HIDDEN

        // Without a dismissal there's no threshold at all. Defaulting it to Long.MIN_VALUE instead
        // would make a missing other-device trigger compare as "after" it and pick the wrong text.
        val visibleFromMs = dismissedAtMs?.let { it + REAPPEAR_AFTER_DISMISSAL_MS }

        if (visibleFromMs != null && triggeredAtMs < visibleFromMs) return State.HIDDEN

        val otherDeviceIsCurrent = otherDeviceTriggeredAtMs != null &&
                (visibleFromMs == null || otherDeviceTriggeredAtMs >= visibleFromMs)

        return if (otherDeviceIsCurrent) State.OTHER_DEVICE else State.GENERAL
    }
}

@Singleton
class UnsupportedMessageBannerStore @Inject constructor(
    private val prefs: PreferenceStorage,
) {
    fun recordTrigger(trigger: UnsupportedMessageBanner.Trigger, nowMs: Long) {
        prefs[TRIGGERED_AT_MS] = nowMs

        if (trigger == UnsupportedMessageBanner.Trigger.OTHER_DEVICE) {
            prefs[OTHER_DEVICE_TRIGGERED_AT_MS] = nowMs
        }
    }

    fun dismiss(nowMs: Long) {
        prefs[DISMISSED_AT_MS] = nowMs
    }

    fun currentState(): UnsupportedMessageBanner.State = UnsupportedMessageBanner.state(
        triggeredAtMs = prefs[TRIGGERED_AT_MS].takeIf { it != UNSET },
        otherDeviceTriggeredAtMs = prefs[OTHER_DEVICE_TRIGGERED_AT_MS].takeIf { it != UNSET },
        dismissedAtMs = prefs[DISMISSED_AT_MS].takeIf { it != UNSET },
    )

    val state: Flow<UnsupportedMessageBanner.State>
        get() = prefs.changes()
            .filter { it.name in KEY_NAMES }
            .map { }
            .onStart { emit(Unit) }
            .map { currentState() }
            .distinctUntilChanged()

    companion object {
        private const val UNSET = -1L

        private val TRIGGERED_AT_MS = PreferenceKey.long("unsupported_message_banner_triggered_at_ms", UNSET)
        private val OTHER_DEVICE_TRIGGERED_AT_MS = PreferenceKey.long("unsupported_message_banner_other_device_triggered_at_ms", UNSET)
        private val DISMISSED_AT_MS = PreferenceKey.long("unsupported_message_banner_dismissed_at_ms", UNSET)

        private val KEY_NAMES = setOf(TRIGGERED_AT_MS.name, OTHER_DEVICE_TRIGGERED_AT_MS.name, DISMISSED_AT_MS.name)
    }
}
