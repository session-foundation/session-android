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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

                        // 2 days is deliberate and load-bearing: it is INSIDE the 7-day window that
                        // gates the expiring CTA (`HomeViewModel`, `validUntil.isBefore(now.plus(7, DAYS))`),
                        // which is what makes this the fixture you pick to eyeball that CTA. The
                        // `_LATER` variant below is the deliberate opposite. Moving this outside 7 days
                        // would make the two behaviourally identical and leave no way to trigger the CTA
                        // by hand.
                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(2),
                            duration = ProSubscriptionDuration.TWELVE_MONTHS.period,
                            providerData = providerMetadata(PAYMENT_PROVIDER_GOOGLE_PLAY, application),
                            quickRefundExpiry = Instant.now() + Duration.ofDays(7),
                            refundInProgress = false
                        )

                        DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER -> ProStatus.Active.Expiring(
                            renewingAt = Instant.now() + Duration.ofDays(EXPIRING_LATER_DAYS),
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

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState): Unit = supervisorScope {
        launch {
            RevocationListPollingWorker.schedule(application)
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

    @OptIn(FlowPreview::class)
    private suspend fun manageProStatusRefreshScheduling() {
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
    }

    private suspend fun manageCurrentProProofRevocation() {
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

    /**
     * Logic to determine if we should animate the avatar for a user or freeze it on the first frame
     */
    fun freezeFrameForUser(recipient: Recipient): Boolean{
        return if(recipient.isCommunityRecipient) false else !recipient.isPro
    }

    /**
     * Returns the max length that a visible message can have based on its Pro status
     */
    fun getIncomingMessageMaxLength(message: VisibleMessage): Int {
        // if the debug is set, return that
        if (prefs.forceIncomingMessagesAsPro()) return MAX_CHARACTER_PRO

        if (message.proFeatures.contains(ProMessageFeature.HIGHER_CHARACTER_LIMIT)) {
            return MAX_CHARACTER_PRO
        }

        return MAX_CHARACTER_REGULAR
    }

    fun getCharacterLimit(isPro: Boolean): Int {
        return if (isPro) MAX_CHARACTER_PRO else MAX_CHARACTER_REGULAR
    }

    fun getPinnedConversationLimit(isPro: Boolean): Int {
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
        pollProStatusAfterPurchase()
    }

    /**
     * After a purchase, chase the ACCOUNT expiry (get_pro_status) — not the proof. The store took the
     * payment but the backend only learns of it out-of-band (an async store notification), so a single
     * refresh right after the purchase usually fires before the backend knows and reads a stale
     * "expired" — and nothing else re-fetches (the proof is still valid so the renewal loop is dormant,
     * and pro_prepaid is suppressed while a proof is held). We deliberately do NOT rotate the proof
     * early (that would leak the subscription change via the rotating seed); we only re-fetch the
     * display-only status, on a bounded poll, until the account flips to active.
     */
    private fun pollProStatusAfterPurchase() {
        scope.launch {
            val repo = proStatusRepository.get()
            // We're done when the ACCOUNT expiry advances past this pre-purchase value — that's the field
            // that moves once the backend redeems the new payment.
            val baselineExpiry = repo.loadState.value.lastUpdated?.first?.expiry
            // Keep firing until it's been ~2 minutes since the FIRST request: an onion-routed fetch can
            // take a while (or time out around 30s), so a short window would only manage one attempt.
            val stopFiringAfter = snodeClock.currentTime().plusMillis(PURCHASE_POLL_WINDOW_MS)
            // Backstop in case a fetch never settles (it should always resolve to Loaded/Error) so we
            // never leave this polling indefinitely.
            withTimeoutOrNull(PURCHASE_POLL_MAX_MS) {
                while (true) {
                    // Fire immediately (over onion routing the request often reaches the backend after
                    // the store's async notification already did).
                    val before = repo.loadState.value.lastUpdated?.second
                    repo.requestRefresh(force = true)
                    // Pace off COMPLETION, not request-start: requestRefresh enqueues with REPLACE, so
                    // re-firing while a slow request is in flight would just cancel and restart it forever.
                    // Wait for THIS fetch to settle — a newer Loaded, or an Error (failure/timeout).
                    val settled = repo.loadState.first { st ->
                        (st is ProStatusRepository.LoadState.Loaded && st.lastUpdated.second != before) ||
                            st is ProStatusRepository.LoadState.Error
                    }
                    val newExpiry = (settled as? ProStatusRepository.LoadState.Loaded)
                        ?.lastUpdated?.first?.expiry
                    if (newExpiry != null && (baselineExpiry == null || newExpiry.isAfter(baselineExpiry))) {
                        break
                    }
                    // Failure/timeout, or a response whose account expiry hasn't advanced yet: wait 5s and
                    // retry, as long as we're still within the ~2-minute window.
                    if (!snodeClock.currentTime().isBefore(stopFiringAfter)) break
                    delay(PURCHASE_POLL_INTERVAL_MS)
                }
            }
        }
    }

    companion object {
        // Bounded post-purchase get_pro_status poll (the backend learns of the payment out-of-band via
        // an async store notification): after each fetch settles, wait 5s and retry until it's been ~2
        // minutes since the first request, so slow/timing-out onion requests still get a few attempts.
        private const val PURCHASE_POLL_INTERVAL_MS = 5_000L
        private const val PURCHASE_POLL_WINDOW_MS = 120_000L
        // Hard backstop (> the window) so a fetch that never settles can't poll forever.
        private const val PURCHASE_POLL_MAX_MS = 150_000L

        // Single-sourced from libsession (see SessionProtocol) rather than hard-coded here.
        //
        // Lazy, and it has to stay that way: SessionProtocol is a LibSessionUtilCApi object, so merely
        // reading one of its constants runs System.loadLibrary("session_util"). Doing that from this
        // companion's initialiser meant ProStatusManager could not be class-initialised anywhere the
        // native library is absent — which is every JVM unit test — so Mockito could not instrument it
        // and every test constructing a ConversationViewModel failed with NoClassDefFoundError.
        // Deferring to first read keeps the constants single-sourced without dragging the native
        // library into class initialisation.
        val MAX_CHARACTER_PRO by lazy { SessionProtocol.PRO_HIGHER_CHARACTER_LIMIT } // max message codepoints for pro users
        private val MAX_CHARACTER_REGULAR by lazy { SessionProtocol.STANDARD_CHARACTER_LIMIT } // max message codepoints for non-pro users
        const val MAX_PIN_REGULAR = 5 // max pinned conversation for non pro users

        const val URL_PRO_SUPPORT = "https://getsession.org/pro-form"

        /**
         * Remaining access for the `EXPIRING_GOOGLE_LATER` debug fixture, in days. **A test pins this
         * value** — don't change it casually.
         *
         * Two non-obvious properties keep that safe, both worth preserving:
         *
         * The label reads "30 days" **only because `DateUtils.getExpiryString` rounds up.** The fixture
         * sets `renewingAt = now + 30d` when `proDataState` recomputes, but the label is rendered from a
         * *later* `now`, so the true remaining is always slightly under 30. That ceiling is load-bearing:
         * make it floor for unrelated reasons and the label silently becomes "29 days".
         *
         * The `Instant.now()` here is understood, not an oversight — the rest of the Pro stack uses
         * `SnodeClock`. It is safe because **both** sides of `Duration.between(now, renewingAt)` read the
         * same device clock, so skew cancels and the ceiling absorbs the remainder. Don't tidy it into
         * `SnodeClock` assuming it is a latent bug; it is safe by that cancellation, not by the clock
         * being right.
         */
        private const val EXPIRING_LATER_DAYS = 30L
    }
}