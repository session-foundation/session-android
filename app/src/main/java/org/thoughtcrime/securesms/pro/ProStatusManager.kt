package org.thoughtcrime.securesms.pro

import android.app.Application
import androidx.collection.ArraySet
import androidx.collection.arraySetOf
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_APP_STORE
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import network.loki.messenger.libsession_util.pro.ProConfig
import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.Util
import network.loki.messenger.libsession_util.util.asSequence
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.pro.api.AddPaymentErrorStatus
import org.thoughtcrime.securesms.pro.api.AddProPaymentApi
import org.thoughtcrime.securesms.pro.api.ProApiResponse
import org.thoughtcrime.securesms.pro.api.ServerApiRequest
import org.thoughtcrime.securesms.pro.db.ProDatabase
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.util.State
import org.thoughtcrime.securesms.util.castAwayType
import java.time.Duration
import java.time.Instant
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProStatusManager @Inject constructor(
    private val application: Application,
    private val prefs: PreferenceStorage,
    @param:ManagerScope private val scope: CoroutineScope,
    private val serverApiExecutor: ServerApiExecutor,
    private val addProPaymentApiFactory: AddProPaymentApi.Factory,
    private val backendConfig: Provider<ProBackendConfig>,
    private val loginState: LoginStateRepository,
    private val proDatabase: ProDatabase,
    private val snodeClock: SnodeClock,
    private val proDetailsRepository: Lazy<ProDetailsRepository>,
    private val configFactory: Lazy<ConfigFactoryProtocol>,
) : AuthAwareComponent {

    val proDataState: StateFlow<ProDataState> = loginState.flowWithLoggedInState {
        combine(
            configFactory.get().userConfigsChanged(onlyConfigTypes = arraySetOf(UserConfigType.USER_PROFILE))
                .castAwayType()
                .onStart { emit(Unit) }
                .map {
                    configFactory.get().withUserConfigs { configs ->
                        configs.userProfile.getProFeatures().contains(ProProfileFeature.PRO_BADGE)
                    }
                }
                .distinctUntilChanged(),
            proDetailsRepository.get().loadState,
            prefs.watch(scope, ProPreferenceKeys.DEBUG_SUBSCRIPTION_STATUS),
            prefs.watch(scope, ProPreferenceKeys.DEBUG_PRO_PLAN_STATUS),
            prefs.watch(scope, ProPreferenceKeys.FORCE_CURRENT_USER_PRO),
        ){ showProBadgePreference, proDetailsState,
           debugSubscription, debugProPlanStatus, forceCurrentUserAsPro ->
            val proDataRefreshState = when(debugProPlanStatus){
                DebugMenuViewModel.DebugProPlanStatus.LOADING -> State.Loading
                DebugMenuViewModel.DebugProPlanStatus.ERROR -> State.Error(Exception())
                else -> {
                    // calculate the real refresh state here
                    when(proDetailsState){
                        is ProDetailsRepository.LoadState.Loading -> {
                            if(proDetailsState.waitingForNetwork) State.Error(Exception())
                            else State.Loading
                        }
                        is ProDetailsRepository.LoadState.Error -> State.Error(Exception())
                        else -> State.Success(Unit)
                    }
                }
            }

            if(!forceCurrentUserAsPro){
                Log.d(DebugLogGroup.PRO_DATA.label, "ProStatusManager: Getting REAL Pro data state")
                val nowMs = snodeClock.currentTimeMillis()

                ProDataState(
                    type = proDetailsState.lastUpdated?.first?.toProStatus(nowMs) ?: ProStatus.NeverSubscribed,
                    showProBadge = showProBadgePreference,
                    refreshState = proDataRefreshState
                )
            }// debug data
            else {
                Log.d(DebugLogGroup.PRO_DATA.label, "ProStatusManager: Getting DEBUG Pro data state")
                val subscriptionState = debugSubscription ?: DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE

                ProDataState(
                    type = when(subscriptionState){
                        DebugMenuViewModel.DebugSubscriptionStatus.AUTO_GOOGLE -> ProStatus.Active.AutoRenewing(
                            renewingAt = Instant.now() + Duration.ofDays(14),
                            duration = ProSubscriptionDuration.THREE_MONTHS,
                            providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!,
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false,
                            inGracePeriod = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE_REFUNDING -> ProStatus.Active.AutoRenewing(
                            renewingAt = Instant.now() + Duration.ofDays(14),
                            duration = ProSubscriptionDuration.THREE_MONTHS,
                            providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!,
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = true,
                            inGracePeriod = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(2),
                            duration = ProSubscriptionDuration.TWELVE_MONTHS,
                            providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!,
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(40),
                            duration = ProSubscriptionDuration.TWELVE_MONTHS,
                            providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY)!!,
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE -> ProStatus.Active.AutoRenewing(
                            renewingAt = Instant.now() + Duration.ofDays(14),
                            duration = ProSubscriptionDuration.ONE_MONTH,
                            providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!,
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false,
                            inGracePeriod = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_APPLE -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(2),
                            duration = ProSubscriptionDuration.ONE_MONTH,
                            providerData = BackendRequests.getPaymentProviderMetadata(PAYMENT_PROVIDER_APP_STORE)!!,
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
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
                    showProBadge = showProBadgePreference,
                )
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly,
        initialValue = getDefaultSubscriptionStateData()
    )

    val postProLaunchStatus: StateFlow<Boolean> = prefs.watch(scope, ProPreferenceKeys.FORCE_POST_PRO)

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState): Unit = supervisorScope {
        launch {
            postProLaunchStatus
                .collectLatest { postLaunch ->
                    if (postLaunch) {
                        RevocationListPollingWorker.schedule(application)
                    } else {
                        RevocationListPollingWorker.cancel(application)
                    }
                }
        }

        launch { manageOtherPeoplePro() }
        launch { manageProDetailsRefreshScheduling() }
        launch { manageCurrentProProofRevocation() }
        launch {
            postProLaunchStatus
                .collectLatest { postLaunch ->
                    if (postLaunch) {
                        RevocationListPollingWorker.schedule(application)
                    } else {
                        RevocationListPollingWorker.cancel(application)
                    }
                }
        }
    }

    override fun onLoggedOut() {
        scope.launch {
            RevocationListPollingWorker.cancel(application)
        }
    }

    private suspend fun manageOtherPeoplePro() {
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

    @OptIn(FlowPreview::class)
    private suspend fun manageProDetailsRefreshScheduling() {
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

                        proDetailsRepository.get().loadState
                            .mapNotNull { it.lastUpdated?.first?.expiry }
                            .distinctUntilChanged()
                            .transformLatest { expiry ->
                                // Schedule a refresh for 30 seconds after access expiry
                                if (snodeClock.delayUntil(expiry.plusSeconds(30))) {
                                    emit("30 seconds after Access expiry reached")
                                }
                            },

                        configFactory.get()
                            .watchUserProConfig()
                            .filterNotNull()
                            .distinctUntilChanged()
                            .mapLatest { proConfig ->
                                val expiry = Instant.ofEpochMilli(proConfig.proProof.expiryMs)
                                // Schedule a refresh for a random number between 10 and 60 minutes before proof expiry

                                val refreshTime =
                                    expiry.minus(Duration.ofMinutes((10..60).random().toLong()))

                                snodeClock.delayUntil(refreshTime)
                                "Pro proof expiry reached"
                            },

                        flowOf("App starting up")
                    ).debounce(500.milliseconds)
                        .collect { refreshReason ->
                            Log.d(
                                DebugLogGroup.PRO_SUBSCRIPTION.label,
                                "Scheduling ProDetails fetch due to: $refreshReason"
                            )

                            proDetailsRepository.get().requestRefresh(force = true)
                        }
                } else {
                    FetchProDetailsWorker.cancel(application)
                }
            }
    }

    private suspend fun manageCurrentProProofRevocation() {
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
        // of if we are in pre-pro world
        if (prefs[ProPreferenceKeys.FORCE_INCOMING_MESSAGE_PRO] || !isPostPro()) return MAX_CHARACTER_PRO

        if (message.proFeatures.contains(ProMessageFeature.HIGHER_CHARACTER_LIMIT)) {
            return MAX_CHARACTER_PRO
        }

        return MAX_CHARACTER_REGULAR
    }

    // Temporary method and concept that we should remove once Pro is out
    fun isPostPro(): Boolean {
        return postProLaunchStatus.value
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
    fun getMessageProFeatures(message: MessageRecord): Set<ProFeature> {
        // use debug values if any
        if(prefs[ProPreferenceKeys.FORCE_INCOMING_MESSAGE_PRO]){
            return buildSet {
                prefs[ProPreferenceKeys.DEBUG_PRO_MESSAGE_FEATURES].toProMessageFeatures(this)
                prefs[ProPreferenceKeys.DEBUG_PRO_PROFILE_FEATURES].toProProfileFeatures(this)
            }
        }

        return message.proFeatures
    }

    /**
     * Adds Pro features, if any, to an outgoing visible message
     */
    fun addProFeatures(message: Message) {
        if (proDataState.value.type !is ProStatus.Active) {
            return
        }

        val proFeatures = ArraySet<ProFeature>()

        configFactory.get().withUserConfigs { configs ->
            proFeatures += configs.userProfile.getProFeatures().asSequence()
        }

        if (message is VisibleMessage &&
                Util.countCodepoints(message.text.orEmpty()) > MAX_CHARACTER_REGULAR){
            proFeatures += ProMessageFeature.HIGHER_CHARACTER_LIMIT
        }

        message.proFeatures = proFeatures
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
                val paymentResponse = runCatching {
                    requireNotNull(withTimeoutOrNull(5000) {
                        serverApiExecutor.execute(
                            ServerApiRequest(
                                proBackendConfig = backendConfig.get(),
                                api = addProPaymentApiFactory.create(
                                    googlePaymentToken = paymentId,
                                    googleOrderId = orderId,
                                    masterPrivateKey = keyData.seeded.proMasterPrivateKey,
                                    rotatingPrivateKey = rotatingKeyPair.secretKey.data
                                )
                            )
                        )
                    }) {
                        "Timeout adding pro payment"
                    }
                }.getOrElse {
                    ProApiResponse.Failure(AddPaymentErrorStatus.GenericError, emptyList())
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
                        proDetailsRepository.get().requestRefresh(force = true)
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
