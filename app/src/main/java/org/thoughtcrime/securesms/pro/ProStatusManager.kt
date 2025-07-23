package org.thoughtcrime.securesms.pro

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProStatusManager @Inject constructor(
    private val prefs: TextSecurePreferences
){
    val MAX_CHARACTER_PRO = 10000 // max characters in a message for pro users
    private val MAX_CHARACTER_REGULAR = 2000 // max characters in a message for non pro users
    private val MAX_PIN_REGULAR = 5 // max pinned conversation for non pro users

    // live state of the Pro status
    private val _proStatus = MutableStateFlow(isCurrentUserPro())
    val proStatus: StateFlow<Boolean> = _proStatus

    // live state for the pre vs post pro launch status
    private val _postProLaunchStatus = MutableStateFlow(isPostPro())
    val postProLaunchStatus: StateFlow<Boolean> = _postProLaunchStatus

    init {
        GlobalScope.launch {
            prefs.watchProStatus().collect {
                _proStatus.update { isCurrentUserPro() }
            }
        }

        GlobalScope.launch {
            prefs.watchPostProStatus().collect {
                _postProLaunchStatus.update { isPostPro() }
            }
        }
    }

    fun isCurrentUserPro(): Boolean {
        // if the debug is set, return that
        if (prefs.forceCurrentUserAsPro()) return true

        // otherwise return the true value
        return false //todo PRO implement real logic once it's in
    }

    fun isUserPro(address: Address?): Boolean{
        //todo PRO implement real logic once it's in
        if(address == null) return false

        if(address.isCommunity) return true // there are no pro status for community so we consider it true so that features work, like animated images for a community
        else if(address.toString() == prefs.getLocalNumber()) return isCurrentUserPro()
        else if(prefs.forceOtherUsersAsPro()) return true

        return false
    }

    /**
     * Logic to determine if we should animate the avatar for a user or freeze it on the first frame
     */
    fun freezeFrameForUser(address: Address?): Boolean{
        return if(!isPostPro()) false else !isUserPro(address)
    }

    /**
     * Returns the max length that a visible message can have based on its Pro status
     */
    fun getIncomingMessageMaxLength(message: VisibleMessage): Int {
        // if the debug is set, return that
        if (prefs.forceIncomingMessagesAsPro()) return MAX_CHARACTER_PRO

        // otherwise return the true value
        return if(isPostPro()) MAX_CHARACTER_REGULAR else MAX_CHARACTER_PRO //todo PRO implement real logic once it's in
    }

    // Temporary method and concept that we should remove once Pro is out
    fun isPostPro(): Boolean {
        return prefs.forcePostPro()
    }

    fun getCharacterLimit(): Int {
        return if (isCurrentUserPro()) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }

    fun getPinnedConversationLimit(): Int {
        if(!isPostPro()) return Int.MAX_VALUE // allow infinite pins while not in post Pro

        return if (isCurrentUserPro()) Int.MAX_VALUE else MAX_PIN_REGULAR

    }
}