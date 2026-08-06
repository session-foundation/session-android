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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_APP_STORE
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import network.loki.messenger.libsession_util.pro.ProConfig
import network.loki.messenger.libsession_util.pro.ProResponseStatus
import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.protocol.SessionProtocol
import network.loki.messenger.libsession_util.util.asSequence
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
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
import org.thoughtcrime.securesms.pro.api.ProApiError
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
    private val prefs: TextSecurePreferences,
    @param:ManagerScope private val scope: CoroutineScope,
    private val serverApiExecutor: ServerApiExecutor,
    private val backendConfig: Provider<ProBackendConfig>,
    private val loginState: LoginStateRepository,
    private val proDatabase: ProDatabase,
    private val snodeClock: SnodeClock,
    private val proStatusRepository: Lazy<ProStatusRepository>,
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
            proStatusRepository.get().loadState,
            (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_SUBSCRIPTION_STATUS } as Flow<*>)
                .onStart { emit(Unit) }
                .map { prefs.getDebugSubscriptionType() },
            (TextSecurePreferences.events.filter { it == TextSecurePreferences.DEBUG_PRO_PLAN_STATUS } as Flow<*>)
                .onStart { emit(Unit) }
                .map { prefs.getDebugProPlanStatus() },
            (TextSecurePreferences.events.filter { it == TextSecurePreferences.SET_FORCE_CURRENT_USER_PRO } as Flow<*>)
                .onStart { emit(Unit) }
                .map { prefs.forceCurrentUserAsPro() },
        ){ showProBadgePreference, proStatusState,
           debugSubscription, debugProPlanStatus, forceCurrentUserAsPro ->
            val proDataRefreshState = when(debugProPlanStatus){
                DebugMenuViewModel.DebugProPlanStatus.LOADING -> State.Loading
                DebugMenuViewModel.DebugProPlanStatus.ERROR -> State.Error(Exception())
                else -> {
                    // calculate the real refresh state here
                    when(proStatusState){
                        is ProStatusRepository.LoadState.Loading -> {
                            if(proStatusState.waitingForNetwork) State.Error(Exception())
                            else State.Loading
                        }
                        is ProStatusRepository.LoadState.Error -> State.Error(Exception())
                        else -> State.Success(Unit)
                    }
                }
            }

            if(!forceCurrentUserAsPro){
                Log.d(DebugLogGroup.PRO_DATA.label, "ProStatusManager: Getting REAL Pro data state")
                val nowMs = snodeClock.currentTimeMillis()

                // Refund-requested is now a synced config flag (set by whichever device — e.g. iOS —
                // initiated the refund), not a get_pro_status field; read it for cross-device display.
                val refundInProgress = configFactory.get()
                    .withUserConfigs { it.userProfile.getRefundRequested() != null }
                ProDataState(
                    type = proStatusState.lastUpdated?.first?.toProStatus(nowMs, application, refundInProgress) ?: ProStatus.NeverSubscribed,
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
                            duration = ProSubscriptionDuration.THREE_MONTHS.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false,
                            inGracePeriod = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE_REFUNDING -> ProStatus.Active.AutoRenewing(
                            renewingAt = Instant.now() + Duration.ofDays(14),
                            duration = ProSubscriptionDuration.THREE_MONTHS.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_APP_STORE, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = true,
                            inGracePeriod = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(2),
                            duration = ProSubscriptionDuration.TWELVE_MONTHS.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(40),
                            duration = ProSubscriptionDuration.TWELVE_MONTHS.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.AUTO_APPLE -> ProStatus.Active.AutoRenewing(
                            renewingAt = Instant.now() + Duration.ofDays(14),
                            duration = ProSubscriptionDuration.ONE_MONTH.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_APP_STORE, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false,
                            inGracePeriod = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_APPLE -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(2),
                            duration = ProSubscriptionDuration.ONE_MONTH.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_APP_STORE, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED -> ProStatus.Expired(
                            expiredAt = Instant.now() - Duration.ofDays(14),
                            providerData = providerMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY, application)
                        )
                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED_EARLIER -> ProStatus.Expired(
                            expiredAt = Instant.now() - Duration.ofDays(60),
                            providerData = providerMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY, application)
                        )
                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED_APPLE -> ProStatus.Expired(
                            expiredAt = Instant.now() - Duration.ofDays(14),
                            providerData = providerMetadata(PAYMENT_PROVIDER_APP_STORE, application)
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

    private val _postProLaunchStatus = MutableStateFlow(isPostPro())
    val postProLaunchStatus: StateFlow<Boolean> = _postProLaunchStatus


    init {
        scope.launch {
            prefs.watchPostProStatus().collect {
                _postProLaunchStatus.update { isPostPro() }
            }
        }
    }

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
        launch { manageProStatusRefreshScheduling() }
        launch { manageCurrentProProofRevocation() }
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
                                convo.proProofInfo?.revocationTag?.let { proDatabase.isRevoked(it.data.toHexString(), snodeClock.currentTime()) } == true
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
    private suspend fun manageProStatusRefreshScheduling() {
        postProLaunchStatus
            .collectLatest { postLaunch ->
                if (postLaunch) {
                    merge(
                        configFactory.get()
                            .userConfigsChanged(EnumSet.of(UserConfigType.USER_PROFILE))
                            .map {
                                configFactory.get().withUserConfigs { configs ->
                                    // Watch both the access expiry (E) and the prepaid marker (I): a
                                    // synced prepaid from another device's purchase must kick the
                                    // redemption poll here too, so any device can pull the entitlement
                                    // through even if the purchasing device goes offline before redeeming.
                                    configs.userProfile.getProAccessExpiry() to
                                        configs.userProfile.getProPrepaid()
                                }
                            }
                            .distinctUntilChanged()
                            .map { "ProAccessExpiry/prepaid in config changes" },

                        proStatusRepository.get().loadState
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
                                val expiry = Instant.ofEpochSecond(proConfig.proProof.expirySeconds)
                                // Wake ~1h before proof expiry so the renewal path runs. Deterministic
                                // (no client-side jitter): per-device random offsets leak device count
                                // via the landed-renewal order statistic; libsession owns the timing
                                // (renewal_target), and config resolution settles concurrent renewals.
                                val refreshTime = expiry.minus(Duration.ofMinutes(60))

                                snodeClock.delayUntil(refreshTime)
                                "Pro proof expiry reached"
                            },

                        flowOf("App starting up")
                    ).debounce(500.milliseconds)
                        .collect { refreshReason ->
                            Log.d(
                                DebugLogGroup.PRO_SUBSCRIPTION.label,
                                "Scheduling ProStatus fetch due to: $refreshReason"
                            )

                            proStatusRepository.get().requestRefresh(force = true)
                        }
                } else {
                    FetchProStatusWorker.cancel(application)
                }
            }
    }

    private suspend fun manageCurrentProProofRevocation() {
        postProLaunchStatus.collectLatest { postLaunch ->
            if (postLaunch) {
                combine(
                    configFactory.get()
                        .watchUserProConfig()
                        .mapNotNull { it?.proProof?.revocationTagHex },

                    proDatabase.revocationChangeNotification
                        .onStart { emit(Unit) },

                    { proofRevocationTag, _ ->
                        proofRevocationTag.takeIf { proDatabase.isRevoked(it, snodeClock.currentTime()) }
                    }
                )
                    .filterNotNull()
                    .collectLatest { revokedHash ->
                        configFactory.get().withMutableUserConfigs { configs ->
                            if (configs.userProfile.getProConfig()?.proProof?.revocationTagHex == revokedHash) {
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
        if (prefs.forceIncomingMessagesAsPro() || !isPostPro()) return MAX_CHARACTER_PRO

        if (message.proFeatures.contains(ProMessageFeature.HIGHER_CHARACTER_LIMIT)) {
            return MAX_CHARACTER_PRO
        }

        return MAX_CHARACTER_REGULAR
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
    fun getMessageProFeatures(message: MessageRecord): Set<ProFeature> {
        // use debug values if any
        if(prefs.forceIncomingMessagesAsPro()){
            return prefs.getDebugMessageFeatures()
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

        if (message is VisibleMessage) {
            // Let libsession own the count -> feature policy: we count codepoints natively and
            // hand it the count; it returns the message feature bitset (e.g. higher char limit).
            val text = message.text.orEmpty()
            SessionProtocol.proFeaturesForMessage(text.codePointCount(0, text.length))
                .toProMessageFeatures(proFeatures)
        }

        message.proFeatures = proFeatures
    }

    /**
     * Called once a purchase has gone through the store. Redemption is now implicit: the store notifies
     * the backend out-of-band and any master-signed request binds the account's unbound payments, so
     * there is no "add payment" call anymore. We record a synced "purchase in flight" marker — so this
     * device and the user's other devices know to poll — and schedule the proof worker, which retries
     * generate_pro_proof with backoff until the backend has the payment and issues a proof (or the
     * 1-week pro_prepaid gate expires). Setting the marker is a no-op inside libsession if already Pro,
     * and it auto-clears once the entitlement lands.
     */
    suspend fun onPurchaseInFlight() {
        val nowSeconds = snodeClock.currentTime().epochSecond
        configFactory.get().withMutableUserConfigs { configs ->
            configs.userProfile.setProPrepaid(nowSeconds)
        }
        Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label, "Purchase in flight; set pro_prepaid, scheduling proof redemption")
        ProProofGenerationWorker.schedule(application)
    }

    companion object {
        // Single-sourced from libsession (see SessionProtocol) rather than hard-coded here. Read
        // through getters, not stored in the initialiser: touching SessionProtocol loads the
        // session_util native library, and doing that from ProStatusManager's <clinit> makes the
        // class impossible to even load (let alone mock) on the JVM, where there is no native
        // library. SessionProtocol already caches both values, so this stays a cheap field read.
        val MAX_CHARACTER_PRO: Int // max message codepoints for pro users
            get() = SessionProtocol.PRO_HIGHER_CHARACTER_LIMIT
        private val MAX_CHARACTER_REGULAR: Int // max message codepoints for non-pro users
            get() = SessionProtocol.STANDARD_CHARACTER_LIMIT
        const val MAX_PIN_REGULAR = 5 // max pinned conversation for non pro users

        const val URL_PRO_SUPPORT = "https://getsession.org/pro-form"
    }
}