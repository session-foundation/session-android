package org.thoughtcrime.securesms.notifications

/**
 * List of all notification IDs used by the app.
 */
object NotificationId {
    const val KEY_CACHING_SERVICE = 4141
    const val WEBRTC_CALL = 313388
    const val TOKEN_DROP = 777
    const val LEGACY_PUSH = 11111

    /**
     * ID for thread based notification. Each thread will be tagged differently.
     */
    const val MESSAGE_THREAD = 5

    /**
     * ID for global message notification (likely just a single message of "You've received a new message")
     */
    const val GLOBAL_MESSAGE = 6
    const val GLOBAL_MESSAGE_SUMMARY = 7
}
