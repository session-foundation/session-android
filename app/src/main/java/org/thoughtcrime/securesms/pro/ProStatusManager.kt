package org.thoughtcrime.securesms.pro

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.ProStatus
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.isPro
import org.session.libsession.utilities.recipients.shouldShowProBadge
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.util.State
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProStatusManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    private val recipientRepository: RecipientRepository,
) : OnAppStartupComponent {

    val subscriptionState: StateFlow<SubscriptionState> = combine(
        recipientRepository.observeSelf(),
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_SUBSCRIPTION_STATUS } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.getDebugSubscriptionType() }
    ){ selfRecipient, debugSubscription ->
        //todo PRO implement properly

        val subscriptionState = debugSubscription ?: DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE
        SubscriptionState(
            type = when(subscriptionState){
                DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE -> SubscriptionType.Active.AutoRenewing(
                    proStatus = ProStatus.Pro(
                        visible = true,
                        validUntil = Instant.now() + Duration.ofDays(14),
                    ),
                    duration = ProSubscriptionDuration.THREE_MONTHS,
                    nonOriginatingSubscription = null
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE -> SubscriptionType.Active.Expiring(
                    proStatus = ProStatus.Pro(
                        visible = true,
                        validUntil = Instant.now() + Duration.ofDays(2),
                    ),
                    duration = ProSubscriptionDuration.TWELVE_MONTHS,
                    nonOriginatingSubscription = null
                )

                DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE -> SubscriptionType.Active.AutoRenewing(
                    proStatus = ProStatus.Pro(
                        visible = true,
                        validUntil = Instant.now() + Duration.ofDays(14),
                    ),
                    duration = ProSubscriptionDuration.ONE_MONTH,
                    nonOriginatingSubscription = SubscriptionType.Active.NonOriginatingSubscription(
                        device = "iPhone",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        urlSubscription = "https://www.apple.com/account/subscriptions",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_APPLE -> SubscriptionType.Active.Expiring(
                    proStatus = ProStatus.Pro(
                        visible = true,
                        validUntil = Instant.now() + Duration.ofDays(2),
                    ),
                    duration = ProSubscriptionDuration.ONE_MONTH,
                    nonOriginatingSubscription = SubscriptionType.Active.NonOriginatingSubscription(
                        device = "iPhone",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        urlSubscription = "https://www.apple.com/account/subscriptions",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED -> SubscriptionType.Expired
            },
               // SubscriptionType.NeverSubscribed,
            refreshState = State.Success(Unit),
        )

    }.stateIn(GlobalScope, SharingStarted.Eagerly,
        initialValue = getDefaultSubscriptionStateData()
    )

    private val _postProLaunchStatus = MutableStateFlow(isPostPro())
    val postProLaunchStatus: StateFlow<Boolean> = _postProLaunchStatus

    init {
        GlobalScope.launch {
            prefs.watchPostProStatus().collect {
                _postProLaunchStatus.update { isPostPro() }
            }
        }
    }

    //todo PRO add "about to expire" CTA logic on app launch
    //todo PRO add "expired" CTA logic on app launch

    /**
     * Logic to determine if we should animate the avatar for a user or freeze it on the first frame
     */
    fun freezeFrameForUser(recipient: Recipient): Boolean{
        return if(!isPostPro() || recipient.isCommunityRecipient) false else !recipient.proStatus.isPro()
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

    fun getCharacterLimit(status: ProStatus): Int {
        return if (status.isPro()) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }

    fun getPinnedConversationLimit(status: ProStatus): Int {
        if(!isPostPro()) return Int.MAX_VALUE // allow infinite pins while not in post Pro

        return if (status.isPro()) Int.MAX_VALUE else MAX_PIN_REGULAR
    }

    /**
     * This will calculate the pro features of an outgoing message
     */
    fun calculateMessageProFeatures(status: ProStatus, message: String): List<MessageProFeature>{
        val features = mutableListOf<MessageProFeature>()

        // check for pro badge display
        if (status.shouldShowProBadge()){
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

    companion object {
        const val MAX_CHARACTER_PRO = 10000 // max characters in a message for pro users
        private const val MAX_CHARACTER_REGULAR = 2000 // max characters in a message for non pro users
        private const val MAX_PIN_REGULAR = 5 // max pinned conversation for non pro users

        const val URL_PRO_SUPPORT = "https://getsession.org/pro-form"
    }
}