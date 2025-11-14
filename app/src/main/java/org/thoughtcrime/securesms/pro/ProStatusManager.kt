package org.thoughtcrime.securesms.pro

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeout
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientProStatus
import org.session.libsession.utilities.recipients.isPro
import org.session.libsession.utilities.recipients.shouldShowProBadge
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.util.State
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProStatusManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    recipientRepository: RecipientRepository,
    @param:ManagerScope private val scope: CoroutineScope,
    loginStateRepository: LoginStateRepository,
) : OnAppStartupComponent {

    val subscriptionState: StateFlow<SubscriptionState> = combine(
        recipientRepository.observeSelf(),
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_SUBSCRIPTION_STATUS } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.getDebugSubscriptionType() },
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_PRO_PLAN_STATUS } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.getDebugProPlanStatus() },
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_CURRENT_USER_PRO } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.forceCurrentUserAsPro() },
    ){ selfRecipient, debugSubscription, debugProPlanStatus, forceCurrentUserAsPro ->
        //todo PRO implement properly

        val subscriptionState = debugSubscription ?: DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE
        val proDataStatus = when(debugProPlanStatus){
            DebugMenuViewModel.DebugProPlanStatus.LOADING -> State.Loading
            DebugMenuViewModel.DebugProPlanStatus.ERROR -> State.Error(Exception())
            else -> State.Success(Unit)
        }

        if(!forceCurrentUserAsPro){
            //todo PRO this is where we should get the real state
            SubscriptionState(
                type = ProStatus.NeverSubscribed,
                showProBadge = selfRecipient.proStatus.shouldShowProBadge,
                refreshState = proDataStatus
            )
        }
        else SubscriptionState(
            type = when(subscriptionState){
                DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE -> ProStatus.Active.AutoRenewing(
                    validUntil = Instant.now() + Duration.ofDays(14),
                    duration = ProSubscriptionDuration.THREE_MONTHS,
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "https://play.google.com/store/account/subscriptions?package=network.loki.messenger&sku=SESSION_PRO_MONTHLY",
                        refundUrl = "https://getsession.org/android-refund",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE -> ProStatus.Active.Expiring(
                    validUntil = Instant.now() + Duration.ofDays(2),
                    duration = ProSubscriptionDuration.TWELVE_MONTHS,
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "https://play.google.com/store/account/subscriptions?package=network.loki.messenger&sku=SESSION_PRO_MONTHLY",
                        refundUrl = "https://getsession.org/android-refund",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER -> ProStatus.Active.Expiring(
                    validUntil = Instant.now() + Duration.ofDays(40),
                    duration = ProSubscriptionDuration.TWELVE_MONTHS,
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "https://play.google.com/store/account/subscriptions?package=network.loki.messenger&sku=SESSION_PRO_MONTHLY",
                        refundUrl = "https://getsession.org/android-refund",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE -> ProStatus.Active.AutoRenewing(
                    validUntil = Instant.now() + Duration.ofDays(14),
                    duration = ProSubscriptionDuration.ONE_MONTH,
                    subscriptionDetails = SubscriptionDetails(
                        device = "iOS",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        subscriptionUrl = "https://www.apple.com/account/subscriptions",
                        refundUrl = "https://support.apple.com/118223",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_APPLE -> ProStatus.Active.Expiring(
                    validUntil = Instant.now() + Duration.ofDays(2),
                    duration = ProSubscriptionDuration.ONE_MONTH,
                    subscriptionDetails = SubscriptionDetails(
                        device = "iOS",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        subscriptionUrl = "https://www.apple.com/account/subscriptions",
                        refundUrl = "https://support.apple.com/118223",
                    )
                )

                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED -> ProStatus.Expired(
                    expiredAt = Instant.now() - Duration.ofDays(14),
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "https://play.google.com/store/account/subscriptions?package=network.loki.messenger&sku=SESSION_PRO_MONTHLY",
                        refundUrl = "https://getsession.org/android-refund",
                    )
                )
                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED_EARLIER -> ProStatus.Expired(
                    expiredAt = Instant.now() - Duration.ofDays(60),
                    subscriptionDetails = SubscriptionDetails(
                        device = "Android",
                        store = "Google Play Store",
                        platform = "Google",
                        platformAccount = "Google account",
                        subscriptionUrl = "https://play.google.com/store/account/subscriptions?package=network.loki.messenger&sku=SESSION_PRO_MONTHLY",
                        refundUrl = "https://getsession.org/android-refund",
                    )
                )
                DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED_APPLE -> ProStatus.Expired(
                    expiredAt = Instant.now() - Duration.ofDays(14),
                    subscriptionDetails = SubscriptionDetails(
                        device = "iOS",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        subscriptionUrl = "https://www.apple.com/account/subscriptions",
                        refundUrl = "https://support.apple.com/118223",
                    )
                )
            },

            refreshState = proDataStatus,
            showProBadge = selfRecipient.proStatus.shouldShowProBadge,
        )

    }.stateIn(scope, SharingStarted.Eagerly,
        initialValue = getDefaultSubscriptionStateData()
    )

    private val _postProLaunchStatus = MutableStateFlow(isPostPro())
    val postProLaunchStatus: StateFlow<Boolean> = _postProLaunchStatus

    init {
        scope.launch {
            prefs.watchPostProStatus().collect {
                _postProLaunchStatus.update { isPostPro() }
            }
        }
    }

    /**
     * Logic to determine if we should animate the avatar for a user or freeze it on the first frame
     */
    fun freezeFrameForUser(recipient: Recipient): Boolean{
        return if(!isPostPro() || recipient.isCommunityRecipient) false else !recipient.proStatus.isPro
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

    fun getCharacterLimit(status: RecipientProStatus?): Int {
        return if (status.isPro) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }

    fun getPinnedConversationLimit(status: RecipientProStatus?): Int {
        if(!isPostPro()) return Int.MAX_VALUE // allow infinite pins while not in post Pro

        return if (status.isPro) Int.MAX_VALUE else MAX_PIN_REGULAR
    }

    /**
     * This will calculate the pro features of an outgoing message
     */
    fun calculateMessageProFeatures(status: RecipientProStatus, message: String): List<MessageProFeature>{
        val features = mutableListOf<MessageProFeature>()

        // check for pro badge display
        if (status.shouldShowProBadge){
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

    suspend fun appProPaymentToBackend() {
        // max 3 attempts as per PRD
        val maxAttempts = 3

        for (attempt in 1..maxAttempts) {
            try {
                // 5s timeout as per PRD
                withTimeout(5_000L) {
                    //todo PRO call AddProPaymentRequest in libsession
                    /**
                     * Here are the errors from the back end that we will need to be aware of
                     * UnknownPayment: retryable > increment counter and try again
                     * Error, ParseError: is non retryable - throw PaymentServerException
                     * Success, AlreadyRedeemed - all good
                     *
                     *
                     *   /// Payment was claimed and the pro proof was successfully generated
                     *     Success = SESSION_PRO_BACKEND_ADD_PRO_PAYMENT_RESPONSE_STATUS_SUCCESS,
                     *
                     *     /// Backend encountered an error when attempting to claim the payment
                     *     Error = SESSION_PRO_BACKEND_ADD_PRO_PAYMENT_RESPONSE_STATUS_ERROR,
                     *
                     *     /// Request JSON failed to be parsed correctly, payload was malformed or missing values
                     *     ParseError = SESSION_PRO_BACKEND_ADD_PRO_PAYMENT_RESPONSE_STATUS_PARSE_ERROR,
                     *
                     *     /// Payment is already claimed
                     *     AlreadyRedeemed = SESSION_PRO_BACKEND_ADD_PRO_PAYMENT_RESPONSE_STATUS_ALREADY_REDEEMED,
                     *
                     *     /// Payment transaction attempted to claim a payment that the backend does not have. Either the
                     *     /// payment doesn't exist or the backend has not witnessed the payment from the provider yet.
                     *     UnknownPayment = SESSION_PRO_BACKEND_ADD_PRO_PAYMENT_RESPONSE_STATUS_UNKNOWN_PAYMENT,
                     */

                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // If not the last attempt, backoff a little and retry
                if (attempt < maxAttempts) {
                    // small incremental backoff before retry
                    val backoffMs = 300L * attempt
                    delay(backoffMs)
                }
            }
        }

        // All attempts failed - throw our custom exception
        throw SubscriptionManager.PaymentServerException()
    }

    enum class MessageProFeature {
        ProBadge, LongMessage, AnimatedAvatar
    }

    companion object {
        const val MAX_CHARACTER_PRO = 10000 // max characters in a message for pro users
        private const val MAX_CHARACTER_REGULAR = 2000 // max characters in a message for non pro users
        const val MAX_PIN_REGULAR = 5 // max pinned conversation for non pro users

        const val URL_PRO_SUPPORT = "https://getsession.org/pro-form"
        const val DEFAULT_GOOGLE_STORE = "Google Play Store"
        const val DEFAULT_APPLE_STORE = "Apple App Store"
    }
}