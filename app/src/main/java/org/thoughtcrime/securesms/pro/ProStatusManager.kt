package org.thoughtcrime.securesms.pro

import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.TextSecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProStatusManager @Inject constructor(
    private val prefs: TextSecurePreferences
){
    private val MAX_CHARACTER_PRO = 10000
    private val MAX_CHARACTER_REGULAR = 2000

    fun isCurrentUserPro(): Boolean {
        // if the debug is set, return that
        if (prefs.forceCurrentUserAsPro()) return true

        // otherwise return the true value
        return false //todo PRO implement real logic once it's in
    }

    /**
     * Returns the max length that a visible message can have based on its Pro status
     */
    fun getIncomingMessageMaxLength(message: VisibleMessage): Int {
        // if the debug is set, return that
        if (prefs.forceIncomingMessagesAsPro()) return MAX_CHARACTER_PRO

        // otherwise return the true value
        return MAX_CHARACTER_REGULAR //todo PRO implement real logic once it's in
    }

    // Temporary method and concept that we should remove once Pro is out
    fun isPostPro(): Boolean {
        return prefs.forcePostPro()
    }

    fun getCharacterLimit(): Int {
        return if (isCurrentUserPro()) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }
}