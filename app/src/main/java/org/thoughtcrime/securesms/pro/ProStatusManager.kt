package org.thoughtcrime.securesms.pro

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.util.AnimatedImageUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProStatusManager @Inject constructor(
    private val prefs: TextSecurePreferences,
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
        //todo PRO implement real logic once it's in - including the specifics for a groupsV2
        if(address == null) return false

        if(address.isCommunity) return false
        else if(address.toString() == prefs.getLocalNumber()) return isCurrentUserPro()
        else if(prefs.forceOtherUsersAsPro()) return true

        return false
    }

    /**
     * Logic to determine if we should animate the avatar for a user or freeze it on the first frame
     */
    fun freezeFrameForUser(address: Address?): Boolean{
        return if(!isPostPro() || address?.isCommunity == true) false else !isUserPro(address)
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

    fun shouldShowProBadge(address: Address?): Boolean {
        return isPostPro() && isUserPro(address) //todo PRO also check flag to see if user wants to hide their badge here
    }

    fun getCharacterLimit(): Int {
        return if (isCurrentUserPro()) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }

    fun getPinnedConversationLimit(): Int {
        if(!isPostPro()) return Int.MAX_VALUE // allow infinite pins while not in post Pro

        return if (isCurrentUserPro()) Int.MAX_VALUE else MAX_PIN_REGULAR
    }


    /**
     * This will calculate the pro features of an outgoing message
     */
    fun calculateMessageProFeatures(message: String): List<MessageProFeature>{
        val userAddress = prefs.getLocalNumber()
        if(!isCurrentUserPro() || userAddress == null) return emptyList()

        val features = mutableListOf<MessageProFeature>()

        // check for pro badge display
        if(shouldShowProBadge(Address.fromSerialized(userAddress))){
            features.add(MessageProFeature.ProBadge)
        }

        // check for "long message" feature
        if(message.length > MAX_CHARACTER_REGULAR){
            features.add(MessageProFeature.LongMessage)
        }

        // check is the user has an animated avatar
        //todo PRO check for animated avatar here and add appropriate feature


        return features
    }

    /**
     * This will get the list of Pro features from an incoming message
     */
    fun getMessageProFeatures(messageId: MessageId): Set<MessageProFeature>{
        //todo PRO implement once we have data

        // use debug values if any
        if(prefs.forceIncomingMessagesAsPro()){
            return prefs.getDebugMessageFeatures()
        }

        return emptySet()
    }

    enum class MessageProFeature {
        ProBadge, LongMessage, AnimatedAvatar
    }
}