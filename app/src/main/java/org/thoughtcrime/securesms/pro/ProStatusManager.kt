package org.thoughtcrime.securesms.pro

import android.app.Application
import androidx.core.content.contentValuesOf
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withTimeout
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_APP_STORE
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import network.loki.messenger.libsession_util.pro.ProConfig
import network.loki.messenger.libsession_util.protocol.ProFeatures
import network.loki.messenger.libsession_util.util.Conversation
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.proFeatures
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.pro.api.AddPaymentErrorStatus
import org.thoughtcrime.securesms.pro.api.AddProPaymentRequest
import org.thoughtcrime.securesms.pro.api.ProApiExecutor
import org.thoughtcrime.securesms.pro.api.ProApiResponse
import org.thoughtcrime.securesms.pro.db.ProDatabase
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.util.State
import java.time.Duration
import java.time.Instant
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProStatusManager @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    recipientRepository: RecipientRepository,
    @param:ManagerScope private val scope: CoroutineScope,
    private val apiExecutor: ProApiExecutor,
    private val loginState: LoginStateRepository,
    private val proDatabase: ProDatabase,
    private val snodeClock: SnodeClock,
    private val proDetailsRepository: ProDetailsRepository,
    private val configFactory: Lazy<ConfigFactoryProtocol>,
) : OnAppStartupComponent {

    val proDataState: StateFlow<ProDataState> = combine(
        recipientRepository.observeSelf().map { it.shouldShowProBadge }.distinctUntilChanged(),
        proDetailsRepository.loadState,
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_SUBSCRIPTION_STATUS } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.getDebugSubscriptionType() },
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_PRO_PLAN_STATUS } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.getDebugProPlanStatus() },
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_CURRENT_USER_PRO } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.forceCurrentUserAsPro() },
    ){ shouldShowProBadge, proDetailsState, debugSubscription, debugProPlanStatus, forceCurrentUserAsPro ->
        val proDataRefreshState = when(debugProPlanStatus){
            DebugMenuViewModel.DebugProPlanStatus.LOADING -> State.Loading
            DebugMenuViewModel.DebugProPlanStatus.ERROR -> State.Error(Exception())
            else -> {
                // calculate the real refresh state here
                when(proDetailsState){
                    is ProDetailsRepository.LoadState.Loading -> State.Loading
                    is ProDetailsRepository.LoadState.Error -> State.Error(Exception())
                    else -> State.Success(Unit)
                }
            }
        }

        if(!forceCurrentUserAsPro){
            Log.d(DebugLogGroup.PRO_DATA.label, "ProStatusManager: Getting REAL Pro data state")

            ProDataState(
                type = proDetailsState.lastUpdated?.first?.toProStatus() ?: ProStatus.NeverSubscribed,
                showProBadge = shouldShowProBadge,
                refreshState = proDataRefreshState
            )
        }// debug data
        else {
            Log.d(DebugLogGroup.PRO_DATA.label, "ProStatusManager: Getting DEBUG Pro data state")
            val subscriptionState = debugSubscription ?: DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE

            ProDataState(
                type = when(subscriptionState){
                    DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE -> ProStatus.Active.AutoRenewing(
                        validUntil = Instant.now() + Duration.ofDays(14),
                        duration = ProSubscriptionDuration.THREE_MONTHS,
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!,
                        quickRefundExpiry = Instant.now() + Duration.ofDays(7)
                    )

                    DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE -> ProStatus.Active.Expiring(
                        validUntil = Instant.now() + Duration.ofDays(2),
                        duration = ProSubscriptionDuration.TWELVE_MONTHS,
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!,
                        quickRefundExpiry = Instant.now() + Duration.ofDays(7)
                    )

                    DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER -> ProStatus.Active.Expiring(
                        validUntil = Instant.now() + Duration.ofDays(40),
                        duration = ProSubscriptionDuration.TWELVE_MONTHS,
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!,
                        quickRefundExpiry = Instant.now() + Duration.ofDays(7)
                    )

                    DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE -> ProStatus.Active.AutoRenewing(
                        validUntil = Instant.now() + Duration.ofDays(14),
                        duration = ProSubscriptionDuration.ONE_MONTH,
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!,
                        quickRefundExpiry = Instant.now() + Duration.ofDays(7)
                    )

                    DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_APPLE -> ProStatus.Active.Expiring(
                        validUntil = Instant.now() + Duration.ofDays(2),
                        duration = ProSubscriptionDuration.ONE_MONTH,
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!,
                        quickRefundExpiry = Instant.now() + Duration.ofDays(7)
                    )

                    DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED -> ProStatus.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(14),
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!
                    )
                    DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED_EARLIER -> ProStatus.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(60),
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!
                    )
                    DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED_APPLE -> ProStatus.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(14),
                        providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!
                    )
                },

                refreshState = proDataRefreshState,
                showProBadge = shouldShowProBadge,
            )
        }

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

        loginState.runWhileLoggedIn(scope) {
            postProLaunchStatus
                .collectLatest { postLaunch ->
                    if (postLaunch) {
                        RevocationListPollingWorker.schedule(application)
                    } else {
                        RevocationListPollingWorker.cancel(application)
                    }
                }
        }

        manageProDetailsRefreshScheduling()
        manageCurrentProProofRevocation()
        manageOtherPeoplePro()
    }

    private fun manageOtherPeoplePro() {
        loginState.runWhileLoggedIn(scope) {
            postProLaunchStatus.collectLatest { postLaunch ->
                if (postLaunch) {
                    merge(
                        configFactory.get().userConfigsChanged(EnumSet.of(UserConfigType.CONVO_INFO_VOLATILE)),
                        proDatabase.revocationChangeNotification,
                    ).onStart { emit(Unit) }
                        .collect {
                            // Go through all convo's pro proof and remove the ones that are revoked
                            val revokedConversations = configFactory.get()
                                .withUserConfigs { it.convoInfoVolatile.all() }
                                .asSequence()
                                .filterIsInstance<Conversation.WithProProofInfo>()
                                .filter { convo ->
                                    convo.proProofInfo?.genIndexHash?.let { proDatabase.isRevoked(it.data.toHexString()) } == true
                                }
                                .onEach { convo ->
                                    convo.proProofInfo = null
                                }
                                .toList()

                            if (revokedConversations.isNotEmpty()) {
                                Log.d(
                                    DebugLogGroup.PRO_DATA.label,
                                    "Clearing Pro proof info for ${revokedConversations.size} conversations due to revocation"
                                )

                                configFactory.get()
                                    .withMutableUserConfigs { configs ->
                                        for (convo in revokedConversations) {
                                            configs.convoInfoVolatile.set(convo)
                                        }
                                    }
                            }
                        }
                }
            }
        }
    }

    private fun manageProDetailsRefreshScheduling() {
        loginState.runWhileLoggedIn(scope) {
            postProLaunchStatus
                .collectLatest { postLaunch ->
                    if (postLaunch) {
                        merge(
                            configFactory.get()
                                .userConfigsChanged(EnumSet.of(UserConfigType.USER_PROFILE))
                                .map {
                                    configFactory.get().withUserConfigs { configs ->
                                        configs.userProfile.getProAccessExpiryMs()
                                    }
                                }
                                .distinctUntilChanged()
                                .map { "ProAccessExpiry in config changes" },

                            proDetailsRepository.loadState
                                .mapNotNull { it.lastUpdated?.first?.expiry }
                                .distinctUntilChanged()
                                .mapLatest { expiry ->
                                    // Schedule a refresh 30seconds after access expiry
                                    val refreshTime = expiry.plusSeconds(30)

                                    val now = snodeClock.currentTime()
                                    if (now < refreshTime) {
                                        val duration = Duration.between(now, refreshTime)
                                        Log.d(
                                            DebugLogGroup.PRO_SUBSCRIPTION.label,
                                            "Delaying ProDetails refresh until $refreshTime due to access expiry"
                                        )
                                        delay(duration)
                                    }

                                    "ProDetails expiry reached"
                                },

                            configFactory.get()
                                .watchUserProConfig()
                                .filterNotNull()
                                .mapLatest { proConfig ->
                                    val expiry = Instant.ofEpochMilli(proConfig.proProof.expiryMs)
                                    // Schedule a refresh for a random number between 10 and 60 minutes before proof expiry
                                    val now = snodeClock.currentTime()

                                    val refreshTime =
                                        expiry.minus(Duration.ofMinutes((10..60).random().toLong()))

                                    if (now < refreshTime) {
                                        Log.d(
                                            DebugLogGroup.PRO_SUBSCRIPTION.label,
                                            "Delaying ProDetails refresh until $refreshTime due to proof expiry"
                                        )
                                        delay(Duration.between(now, expiry))
                                    }
                                },

                            flowOf("App starting up")
                        ).collect { refreshReason ->
                            Log.d(
                                DebugLogGroup.PRO_SUBSCRIPTION.label,
                                "Scheduling ProDetails fetch due to: $refreshReason"
                            )

                            proDetailsRepository.requestRefresh()
                        }
                    } else {
                        FetchProDetailsWorker.cancel(application)
                    }
                }
        }
    }

    private fun manageCurrentProProofRevocation() {
        loginState.runWhileLoggedIn(scope) {
            postProLaunchStatus.collectLatest { postLaunch ->
                if (postLaunch) {
                    combine(
                        configFactory.get()
                            .watchUserProConfig()
                            .mapNotNull { it?.proProof?.genIndexHashHex },

                        proDatabase.revocationChangeNotification
                            .onStart { emit(Unit) },

                        { proofGenIndexHash, _ ->
                            proofGenIndexHash.takeIf { proDatabase.isRevoked(it) }
                        }
                    )
                        .filterNotNull()
                        .collectLatest { revokedHash ->
                            configFactory.get().withMutableUserConfigs { configs ->
                                if (configs.userProfile.getProConfig()?.proProof?.genIndexHashHex == revokedHash) {
                                    Log.w(
                                        DebugLogGroup.PRO_SUBSCRIPTION.label,
                                        "Current Pro proof has been revoked, clearing Pro config"
                                    )
                                    configs.userProfile.removeProConfig()
                                }
                            }
                        }
                }
            }
        }
    }

    /**
     * Logic to determine if we should animate the avatar for a user or freeze it on the first frame
     */
    fun freezeFrameForUser(recipient: Recipient): Boolean{
        return if(!isPostPro() || recipient.isCommunityRecipient) false else !recipient.isPro
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

    fun getCharacterLimit(isPro: Boolean): Int {
        return if (isPro) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }

    fun getPinnedConversationLimit(isPro: Boolean): Int {
        if(!isPostPro()) return Int.MAX_VALUE // allow infinite pins while not in post Pro

        return if (isPro) Int.MAX_VALUE else MAX_PIN_REGULAR
    }

    /**
     * This will get the list of Pro features from an incoming message
     */
    fun getMessageProFeatures(message: MessageRecord): ProFeatures {
        // use debug values if any
        if(prefs.forceIncomingMessagesAsPro()){
            return prefs.getDebugMessageFeatures()
        }

        return message.proFeatures
    }

    /**
     * To be called once a subscription has successfully gone through a provider.
     * This will link that payment to our back end.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun addProPayment(orderId: String, paymentId: String) {
        // max 3 attempts as per PRD
        val maxAttempts = 3

        // no point in going further if we have no key data
        val keyData = loginState.loggedInState.value ?: throw Exception()
        val rotatingKeyPair = ED25519.generate(null)

        for (attempt in 1..maxAttempts) {
            try {
                // 5s timeout as per PRD
                val paymentResponse = withTimeout(5_000L) {
                    apiExecutor.executeRequest(
                        request = AddProPaymentRequest(
                            googlePaymentToken = paymentId,
                            googleOrderId = orderId,
                            masterPrivateKey = keyData.seeded.proMasterPrivateKey,
                            rotatingPrivateKey = rotatingKeyPair.secretKey.data
                        )
                    )
                }

                when (paymentResponse) {
                    is ProApiResponse.Success -> {
                        Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label, "Backend 'add pro payment' successful")
                        // Payment was successfully claimed - save it
                        configFactory.get().withMutableUserConfigs { configs ->
                            configs.userProfile.setProConfig(
                                ProConfig(
                                    proProof = paymentResponse.data,
                                    rotatingPrivateKey = rotatingKeyPair.secretKey.data
                                )
                            )

                            configs.userProfile.setProBadge(true)
                        }
                        // refresh the pro details
                        proDetailsRepository.requestRefresh()
                    }

                    is ProApiResponse.Failure -> {
                        // Handle payment failure
                        Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "Backend 'add pro payment' failure: $paymentResponse")
                        when (paymentResponse.status) {
                            // unknown payment is retryable - throw a generic exception here to go through our retries
                            AddPaymentErrorStatus.UnknownPayment -> {
                                throw Exception()
                            }

                            // nothing to do if already redeemed
                            AddPaymentErrorStatus.AlreadyRedeemed -> {
                                return
                            }

                            // non retryable error - throw our custom exception
                            AddPaymentErrorStatus.GenericError -> {
                                throw SubscriptionManager.PaymentServerException()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionManager.PaymentServerException){
                // rethrow this error directly without retrying
                Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "Backend 'add pro payment' PaymentServerException caught and rethrown")
                throw e
            }catch (e: Exception) {
                Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "Backend 'add pro payment' exception", e)
                // If not the last attempt, backoff a little and retry
                if (attempt < maxAttempts) {
                    // small incremental backoff before retry
                    val backoffMs = 300L * attempt
                    delay(backoffMs)
                }
            }
        }

        // All attempts failed - throw our custom exception
        Log.w(DebugLogGroup.PRO_SUBSCRIPTION.label, "Backend 'add pro payment' - Al retries attempted, throwing our custom `PaymentServerException`")
        throw SubscriptionManager.PaymentServerException()
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