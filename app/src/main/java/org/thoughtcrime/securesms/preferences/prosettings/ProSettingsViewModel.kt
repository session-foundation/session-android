package org.thoughtcrime.securesms.preferences.prosettings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavOptionsBuilder
import org.session.libsession.utilities.Phrase
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import org.session.libsession.database.StorageProtocol
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.ACTION_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.MONTHLY_PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PERCENT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SELECTED_PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SELECTED_PLAN_LENGTH_SINGULAR_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProDataState
import org.thoughtcrime.securesms.pro.ProStatusRepository
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.getDefaultSubscriptionStateData
import org.thoughtcrime.securesms.pro.isFromAnotherPlatform
import org.thoughtcrime.securesms.pro.subscription.ProPlanPeriod
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionCoordinator
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.ui.dialog.SimpleDialogData
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.CurrencyFormatter
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.State
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ProSettingsViewModel.Factory::class)
class ProSettingsViewModel @AssistedInject constructor(
    @Assisted private val navigator: UINavigator<ProSettingsDestination>,
    @param:ApplicationContext private val context: Context,
    private val proStatusManager: ProStatusManager,
    private val subscriptionCoordinator: SubscriptionCoordinator,
    private val dateUtils: DateUtils,
    private val prefs: TextSecurePreferences,
    private val proStatusRepository: ProStatusRepository,
    private val configFactory: Lazy<ConfigFactoryProtocol>,
    private val storage: StorageProtocol,
    private val clock: SnodeClock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(navigator: UINavigator<ProSettingsDestination>): ProSettingsViewModel
    }

    private val _proSettingsUIState: MutableStateFlow<ProSettingsState> = MutableStateFlow(ProSettingsState())
    val proSettingsUIState: StateFlow<ProSettingsState> = _proSettingsUIState

    private val _dialogState: MutableStateFlow<DialogsState> = MutableStateFlow(DialogsState())
    val dialogState: StateFlow<DialogsState> = _dialogState

    private val _choosePlanState: MutableStateFlow<State<ChoosePlanState>> = MutableStateFlow(State.Loading)
    val choosePlanState: StateFlow<State<ChoosePlanState>> = _choosePlanState

    private val _refundPlanState: MutableStateFlow<State<RefundPlanState>> = MutableStateFlow(State.Loading)
    val refundPlanState: StateFlow<State<RefundPlanState>> = _refundPlanState

    private val _cancelPlanState: MutableStateFlow<State<CancelPlanState>> = MutableStateFlow(State.Loading)
    val cancelPlanState: StateFlow<State<CancelPlanState>> = _cancelPlanState

    private var recovering: Boolean = false

    init {
        // Trigger #3 — refresh on entering Pro settings. Floored, not `immediate`: this screen is
        // the one place the status is actually read, so it should not be showing a value from an
        // arbitrarily old background fetch, but arriving here is not on its own a reason to bypass
        // the 60s floor.
        //
        // Deliberately NOT via refreshProStatus(): that early-returns while `refreshState` is
        // Loading, and a process that hasn't confirmed a fetch of its own reports Loading from
        // launch. Routing on-enter through that guard would mean the one trigger able to
        // resolve the state is the one the state suppresses — a spinner that never clears, which is
        // the failure iOS hit from the same direction. The repository single-flights anyway
        // (WorkManager REPLACE), so the guard buys nothing here.
        proStatusRepository.requestRefresh(immediate = false)

        // Trigger #4 — the bounded grace poll, for as long as this screen is open.
        pollProStatusDuringGraceWhileOpen()

        // observe subscription status
        viewModelScope.launch {
            proStatusManager
                .proDataState
                .collectLatest(::generateState)
        }

        // observe purchase events
        viewModelScope.launch {
            subscriptionCoordinator.getCurrentManager().purchaseEvents.collect { purchaseEvent ->
                val data = choosePlanState.value

                // stop loader
                if(data is State.Success) {
                    _choosePlanState.update {
                        State.Success(
                            data.value.copy(purchaseInProgress = false)
                        )
                    }
                }

                when(purchaseEvent){
                    is SubscriptionManager.PurchaseEvent.Success -> {
                        navigator.navigate(destination = ProSettingsDestination.PlanConfirmation)
                    }

                    is SubscriptionManager.PurchaseEvent.Failed.GenericError -> {
                        Toast.makeText(
                            context,
                            purchaseEvent.errorMessage ?: context.getString(R.string.errorGeneric),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    is SubscriptionManager.PurchaseEvent.Cancelled -> {
                        // nothing to do in this case
                    }
                }
            }
        }
    }

    private suspend fun generateState(proDataState: ProDataState){
        val subType = proDataState.type

        // calculate stats for pro users
        if (subType is ProStatus.Active) refreshProStats()

        // we got a new state - if we were recovering, we can mark it as done
        if(proDataState.refreshState is State.Success && recovering){
            // we are back with a state after attempting to recover
            // show a confirmation dialog whose text depends on the current pro status
            // if we are now pro after recovery:
            if(proDataState.type is ProStatus.Active){
                _dialogState.update {
                    it.copy(
                        showSimpleDialog = SimpleDialogData(
                            title = Phrase.from(context, R.string.proAccessRestored)
                            .format().toString(),
                            message = Phrase.from(context, R.string.proAccessRestoredDescription)
                                .format(),
                            positiveText = context.getString(R.string.okay),
                            positiveStyleDanger = false,
                        )
                    )
                }
            } else {
                _dialogState.update {
                    it.copy(
                        showSimpleDialog = SimpleDialogData(
                            title = Phrase.from(context, R.string.proAccessNotFound)
                                .format().toString(),
                            message = Phrase.from(context, R.string.proAccessNotFoundDescription)
                                .format(),
                            positiveText = context.getString(R.string.helpSupport),
                            negativeText = context.getString(R.string.close),
                            positiveStyleDanger = false,
                            negativeStyleDanger = true,
                            onPositive = { onCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT)) },
                        )
                    )
                }
            }
        }

        // clear recovery on non loads
        if(proDataState.refreshState !is State.Loading){
            recovering = false
        }

        // The grace warning's "a completed fetch at or after the crossing" condition is applied
        // inside `toProStatus`, where inGracePeriod is produced — so `subType.inGracePeriod` is
        // already safe to read directly here and at the label below. It used to be gated at each
        // consumer instead, which meant a new reader inherited no protection.
        while (true) {
            val now = clock.currentTime()

            _proSettingsUIState.update {
                it.copy(
                    proDataState = proDataState,
                    inGracePeriod = (subType as? ProStatus.Active.AutoRenewing)?.inGracePeriod == true,
                    subscriptionExpiryLabel = when(subType){
                        is ProStatus.Active.AutoRenewing -> {
                            // in grace period — already debounced at construction (see toProStatus)
                            if(subType.inGracePeriod) {
                                Phrase.from(context, R.string.proRenewalUnsuccessful)
                                    .format()
                            } else {
                                Phrase.from(context, R.string.proAutoRenewTime)
                                    .put(
                                        TIME_KEY, dateUtils.getExpiryString(
                                            remaining = Duration.between(now, subType.renewingAt)
                                                .coerceAtLeast(Duration.ZERO)
                                        )
                                    )
                                    .format()
                            }
                        }

                        is ProStatus.Active.Expiring ->
                            Phrase.from(context, R.string.proExpiringTime)
                                .put(TIME_KEY, dateUtils.getExpiryString(
                                    remaining = Duration.between(now, subType.renewingAt)
                                        .coerceAtLeast(Duration.ZERO)))
                                .format()

                        else -> ""
                    },
                    subscriptionExpiryDate = when(subType){
                        is ProStatus.Active -> subType.renewingAtFormatted()
                        else -> ""
                    },
                )
            }

            if (subType is ProStatus.Active.AutoRenewing || subType is ProStatus.Active.Expiring) {
                if (subType.renewingAt.isAfter(now)) {
                    val secondsTilExpired = subType.renewingAt.epochSecond - now.epochSecond
                    if (secondsTilExpired > 120) {
                        // Tick every minute
                        delay(1.minutes)
                    } else if (secondsTilExpired > 60) {
                        // Tick once until we reach the last minute
                        delay((secondsTilExpired - 60).seconds)
                    } else {
                        // Tick every seconds
                        delay(1.seconds)
                    }
                } else {
                    break // subscription is supposed to be expired now
                }
            } else {
                break  // pro not active, no need to refresh any UI
            }
        }
    }

    fun ensureChoosePlanState(){
        // Get the choose plan state ready in loading mode
        _choosePlanState.update { State.Loading }

        // while the user is on the page we need to calculate the "choose plan" data
        viewModelScope.launch {
            val subType = _proSettingsUIState.value.proDataState.type

            // first check if the user has a valid subscription and billing
            val hasBillingCapacity = subscriptionCoordinator.getCurrentManager().supportsBilling.value
            val hasValidSub = subscriptionCoordinator.getCurrentManager().hasValidSubscription()

            // next get the plans, including their pricing, unless there is no billing
            // or the user is pro without a valid subscription
            // or the user is pro but non originating
            val noPriceNeeded = !hasBillingCapacity
                    || (subType is ProStatus.Active && !hasValidSub)
                    || (subType is ProStatus.Active && subType.providerData.isFromAnotherPlatform())

            val plans = if(noPriceNeeded) emptyList()
            else {
                // attempt to get the prices from the subscription provider
                // return early in case of error
                try {
                    getSubscriptionPlans(subType)
                } catch (e: Exception){
                    Log.d(DebugLogGroup.PRO_SUBSCRIPTION.label, "Error while trying to get subscription plans", e)
                    _choosePlanState.update { State.Error(e) }
                    return@launch
                }
            }

            _choosePlanState.update {
                State.Success(
                    ChoosePlanState(
                        proStatus = subType,
                        hasValidSubscription = hasValidSub,
                        hasBillingCapacity = hasBillingCapacity,
                        enableButton = subType !is ProStatus.Active.AutoRenewing, // only the auto-renew can have a disabled state
                        plans = plans
                    )
                )
            }
        }
    }

    fun ensureCancelState(){
        val sub = _proSettingsUIState.value.proDataState.type
        if(sub !is ProStatus.Active) return

        _cancelPlanState.update { State.Loading }
        viewModelScope.launch {
            _cancelPlanState.update { State.Loading }
            val hasValidSubscription = subscriptionCoordinator.getCurrentManager().hasValidSubscription()

            _cancelPlanState.update {
                State.Success(
                    CancelPlanState(
                        proStatus = sub,
                        hasValidSubscription = hasValidSubscription
                    )
                )
            }
        }
    }

    fun ensureRefundState(){
        val sub = _proSettingsUIState.value.proDataState.type
        if(sub !is ProStatus.Active) return

        _refundPlanState.update { State.Loading }

        viewModelScope.launch {
            _refundPlanState.update {
                val isQuickRefund = if(prefs.forceCurrentUserAsPro()) prefs.getDebugIsWithinQuickRefund()// debug mode
                else sub.isWithinQuickRefundWindow(clock.currentTime())

                State.Success(
                    RefundPlanState(
                        proStatus = sub,
                        isQuickRefund = isQuickRefund,
                        quickRefundUrl = sub.providerData.refundPlatformUrl
                    )
                )
            }
        }
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ShowOpenUrlDialog -> {
                _dialogState.update {
                    it.copy(openLinkDialogUrl = command.url)
                }
            }

            is Commands.GoToChoosePlan -> {
                when(_proSettingsUIState.value.proDataState.refreshState){
                    // if we are in a loading or refresh state we should show a dialog instead
                    is State.Loading -> {
                        val state = _proSettingsUIState.value.proDataState.type
                        val (title, message) = when{
                            state is ProStatus.Active -> Phrase.from(context.getText(R.string.proAccessLoading))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proAccessLoadingDescription))
                                        .format()
                            state is ProStatus.NeverSubscribed
                                    || command.inSheet -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusContinue))
                                        .format()
                            else -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusRenew))
                                        .format()
                        }

                        _dialogState.update {
                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = title,
                                    message = message,
                                    positiveText = context.getString(R.string.okay),
                                    positiveStyleDanger = false,
                                )
                            )
                        }
                    }

                    is State.Error -> {
                        val state = _proSettingsUIState.value.proDataState.type
                        val (title, message) = when{
                            state is ProStatus.Active -> Phrase.from(context.getText(R.string.proAccessError))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proAccessNetworkLoadError))
                                        .format()
                            state is ProStatus.NeverSubscribed
                                    || command.inSheet-> Phrase.from(context.getText(R.string.proStatusError))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusNetworkErrorContinue))
                                        .format()
                            else -> Phrase.from(context.getText(R.string.proStatusError))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusRenewError))
                                        .format()
                        }

                        _dialogState.update {
                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = title,
                                    message = message,
                                    positiveText = context.getString(R.string.retry),
                                    negativeText = context.getString(R.string.helpSupport),
                                    positiveStyleDanger = false,
                                    showXIcon = true,
                                    onPositive = { refreshProStatus(true) },
                                    onNegative = {
                                        onCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT))
                                    }
                                )
                            )
                        }
                    }

                    // Not loading nor error. If in grace period show a dialog
                    // otherwise go to the "choose plan" screen
                    else -> {
                        // if we in the process of refunding on another platform, show that screen instead
                        if((_proSettingsUIState.value.proDataState.type as? ProStatus.Active)?.refundInProgress == true){
                            navigateTo(ProSettingsDestination.RefundInProgress)
                            return
                        }

                        // otherwise handle the "Choose Plan"
                        val provider = (_proSettingsUIState.value.proDataState.type as? ProStatus.Active)?.providerData
                        if(_proSettingsUIState.value.inGracePeriod){
                            _dialogState.update {
                                it.copy(
                                    showSimpleDialog = SimpleDialogData(
                                        title = Phrase.from(context, R.string.proRenewalUnsuccessfulTitle)
                                            .format().toString(),
                                        message = Phrase.from(context, R.string.proUnsuccessfulRenewalDescription)
                                            .put(PLATFORM_ACCOUNT_KEY, provider?.platformAccount ?: "")
                                            .put(PLATFORM_STORE_KEY, provider?.store ?: "")
                                            .format(),
                                        positiveText = context.getString(R.string.theContinue),
                                        positiveStyleDanger = false,
                                        onPositive = {
                                            goToChoosePlan()
                                        },
                                        showXIcon = true
                                    )
                                )
                            }
                        } else {
                            goToChoosePlan()
                        }
                    }
                }
            }

            Commands.GoToRefund -> {
                val sub = _proSettingsUIState.value.proDataState.type
                if(sub !is ProStatus.Active) return

                navigateTo(ProSettingsDestination.RefundSubscription)
            }

            Commands.GoToCancel -> {
                val sub = _proSettingsUIState.value.proDataState.type
                if(sub !is ProStatus.Active) return

                navigateTo(ProSettingsDestination.CancelSubscription)
            }

            Commands.OnPostPlanConfirmation -> {
                // send a custom action to deal with "post plan confirmation"
                viewModelScope.launch {
                    navigator.sendCustomAction(ProNavHostCustomActions.ON_POST_PLAN_CONFIRMATION)
                }
            }

            Commands.OpenCancelSubscriptionPage -> {
                val subUrl = (_proSettingsUIState.value.proDataState.type as? ProStatus.Active)
                    ?.providerData?.cancelSubscriptionUrl
                if(!subUrl.isNullOrEmpty()){
                    viewModelScope.launch {
                        navigator.navigateToIntent(
                            Intent(Intent.ACTION_VIEW, subUrl.toUri())
                        )
                    }
                }
            }

            is Commands.SetShowProBadge -> {
                configFactory.get().withMutableUserConfigs { configs ->
                    configs.userProfile.setProBadge(command.show)
                }
            }

            is Commands.RecoverAccount -> {
                recovering = true
                refreshProStatus(true)
            }

            is Commands.OnUserBackFromCancellation -> {
                // refresh details
                refreshProStatus(true)

                // send action to handle post cancellation to the navigator
                viewModelScope.launch {
                    navigator.sendCustomAction(ProNavHostCustomActions.ON_POST_CANCELLATION)
                }
            }

            is Commands.SelectProPlan -> {
                val data: ChoosePlanState = (_choosePlanState.value as? State.Success)?.value ?: return

                _choosePlanState.update {
                    State.Success(
                        data.copy(
                            plans = data.plans.map {
                                it.copy(selected = it == command.plan)
                            },
                            enableButton = data.proStatus !is ProStatus.Active.AutoRenewing
                                    || !command.plan.currentPlan
                        )
                    )
                }
            }

            Commands.ShowTCPolicyDialog -> {
                _dialogState.update {
                    it.copy(showTCPolicyDialog = true)
                }
            }

            Commands.HideTCPolicyDialog -> {
                _dialogState.update {
                    it.copy(showTCPolicyDialog = false)
                }
            }

            Commands.GetProPlan -> {
                val currentSubscription = _proSettingsUIState.value.proDataState.type
                val selectedPlan = getSelectedPlan() ?: return

                if(currentSubscription is ProStatus.Active){
                    val newSubscriptionExpiryString = currentSubscription.renewingAtFormatted()

                    val currentSubscriptionDuration = DateUtils.getLocalisedProPlanLength(
                        context, currentSubscription.duration
                    )

                    val selectedSubscriptionDuration = DateUtils.getLocalisedProPlanLength(
                        context, selectedPlan.durationType.period
                    )

                    _dialogState.update {
                        it.copy(
                            showSimpleDialog = SimpleDialogData(
                                title = Phrase.from(context, R.string.updateAccess)
                                    .format().toString(),
                                message = if(currentSubscription is ProStatus.Active.AutoRenewing)
                                    Phrase.from(context.getText(R.string.proUpdateAccessDescription))
                                        .put(DATE_KEY, newSubscriptionExpiryString)
                                        .put(CURRENT_PLAN_LENGTH_KEY, currentSubscriptionDuration)
                                        .put(SELECTED_PLAN_LENGTH_KEY, selectedSubscriptionDuration.lowercase())
                                        // for this string below, we want to remove the 's' at the end if there is one: 12 Months becomes 12 Month
                                        .put(SELECTED_PLAN_LENGTH_SINGULAR_KEY, selectedSubscriptionDuration.removeSuffix("s"))
                                        .format()
                                else Phrase.from(context.getText(R.string.proUpdateAccessExpireDescription))
                                    .put(DATE_KEY, newSubscriptionExpiryString)
                                    .put(SELECTED_PLAN_LENGTH_KEY, selectedSubscriptionDuration.lowercase())
                                    .format(),
                                positiveText = context.getString(R.string.update),
                                negativeText = context.getString(R.string.cancel),
                                positiveStyleDanger = false,
                                onPositive = { getPlanFromProvider() },
                                onNegative = { onCommand(Commands.HideTCPolicyDialog) }
                            )
                        )
                    }
                }
                // otherwise go straight to the store
                else {
                    getPlanFromProvider()
                }
            }

            Commands.ConfirmProPlan -> {
                getPlanFromProvider()
            }

            Commands.HideSimpleDialog -> {
                _dialogState.update {
                    it.copy(showSimpleDialog = null)
                }
            }

            is Commands.OnHeaderClicked -> {
                when(_proSettingsUIState.value.proDataState.refreshState){
                    // if we are in a loading or refresh state we should show a dialog instead
                    is State.Loading -> {
                        val state = _proSettingsUIState.value.proDataState.type
                        val (title, message) = when{
                            state is ProStatus.Active -> Phrase.from(context.getText(R.string.proStatusLoading))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusLoadingDescription))
                                        .format()
                            state is ProStatus.NeverSubscribed
                                    || command.inSheet-> Phrase.from(context.getText(R.string.checkingProStatus))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusContinue))
                                        .format()
                            else -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusDescription))
                                        .format()
                        }
                        _dialogState.update {
                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = title,
                                    message = message,
                                    positiveText = context.getString(R.string.okay),
                                    positiveStyleDanger = false,
                                )
                            )
                        }
                    }

                    is State.Error -> {
                        _dialogState.update {
                            val state = _proSettingsUIState.value.proDataState.type
                            val (title, message) = when{
                                state is ProStatus.Active -> Phrase.from(context.getText(R.string.proStatusError))
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusRefreshNetworkError))
                                            .format()
                                state is ProStatus.NeverSubscribed ||
                                     command.inSheet -> Phrase.from(context.getText(R.string.proStatusError))
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusNetworkErrorContinue))
                                            .format()
                                else -> Phrase.from(context.getText(R.string.proStatusError))
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusRefreshNetworkError))
                                            .format()
                            }

                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = title,
                                    message = message,
                                    positiveText = context.getString(R.string.retry),
                                    negativeText = context.getString(R.string.helpSupport),
                                    positiveStyleDanger = false,
                                    showXIcon = true,
                                    onPositive = { refreshProStatus(true) },
                                    onNegative = {
                                        onCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT))
                                    }
                                )
                            )
                        }
                    }

                    else -> {}
                }
            }

            Commands.OnProStatsClicked -> {
                when(_proSettingsUIState.value.proStats){
                    // if we are in a loading or refresh state we should show a dialog instead
                    is State.Loading -> {
                        _dialogState.update {
                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = Phrase.from(context.getText(R.string.proStatsLoading))
                                        .format().toString(),
                                    message = Phrase.from(context.getText(R.string.proStatsLoadingDescription))
                                        .format(),
                                    positiveText = context.getString(R.string.okay),
                                    positiveStyleDanger = false,
                                )
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    /**
     * Trigger #4 — poll `get_pro_status` while the renewal is overdue and this screen is open.
     *
     * Deliberately NOT a 60s timer on the screen. It sleeps until the renewal falls due and only
     * then polls, once a minute, while the renewal still hasn't landed. Three things stop it: the
     * renewal arrives (`expiry` advances, which restarts this from the new date), the account runs
     * past coverage, or the screen closes and cancels `viewModelScope`.
     *
     * Note it needs no `auto_renewing` check. The wire zeroes `grace_period_duration` when the
     * subscription isn't auto-renewing, so for those accounts coverage ends exactly when the
     * renewal falls due and the loop below never runs a single iteration — there is no renewal in
     * flight to poll for, and the arithmetic already says so.
     *
     * Exempt from the freshness floor (spec §4: bounded polls carry their own cadence and their own
     * termination). At 60s this poll sits exactly on the 60s floor, so leaving it floored would drop
     * ticks to timing jitter alone.
     */
    private fun pollProStatusDuringGraceWhileOpen() {
        viewModelScope.launch {
            proStatusRepository.loadState
                .map { it.lastUpdated?.first }
                .distinctUntilChanged { old, new -> old?.expiry == new?.expiry }
                .collectLatest { status ->
                    val renewalDue = status?.renewalDueAt() ?: return@collectLatest
                    val coverageEnd = status.coverageEndsAt() ?: return@collectLatest

                    // Returns immediately when already past it — i.e. the screen was opened
                    // mid-grace — which is exactly when we want the first poll to be now.
                    clock.delayUntil(renewalDue)

                    while (clock.currentTime().isBefore(coverageEnd)) {
                        proStatusRepository.requestRefresh(immediate = true)
                        delay(GRACE_POLL_INTERVAL_MS)
                    }
                }
        }
    }

    /**
     * The instant the renewal falls due.
     *
     * `expiry` IS the payment-due date. Coverage runs a further `gracePeriod` past it — the backend's
     * contract is "`expiry_ts` + `grace_period_duration` is exactly when we stop serving" — so do not
     * subtract to get this instant.
     */
    private fun GetProStatusResponse.renewalDueAt(): Instant? = expiry

    /** The instant coverage really ends. See [renewalDueAt]. */
    private fun GetProStatusResponse.coverageEndsAt(): Instant? = expiry?.plus(gracePeriod)

    /**
     * [immediate] bypasses the repository's freshness floor. Every caller here is a user-initiated
     * refresh (a retry button, recover, returning from cancellation) — trigger #5 — so they pass
     * true: the user is looking at the screen waiting for the answer.
     */
    private fun refreshProStatus(immediate: Boolean){
        // stop early if we are already refreshing
        if(_proSettingsUIState.value.proDataState.refreshState is State.Loading) return

        // refreshes the pro status data
        proStatusRepository.requestRefresh(immediate = immediate)
    }

    private fun getSelectedPlan(): ProPlan? {
        return (_choosePlanState.value as? State.Success)?.value?.plans?.firstOrNull { it.selected }
    }

    private fun goToChoosePlan(){
        // Navigate to choose plan screen
        navigateTo(ProSettingsDestination.ChoosePlan)
    }

    private suspend fun getSubscriptionPlans(subType: ProStatus): List<ProPlan> {
        // The active plan's raw (count, unit), or null when not subscribed. We mark/disable a catalog SKU
        // as the user's "current" plan by matching this period against the SKU's own (count, unit) — NOT
        // by a fixed enum, so the unit is respected as transmitted. This is cosmetic and (count, unit) is
        // not a guaranteed-unique key, so we degrade gracefully: a SKU is "current" iff its period equals
        // the active plan's; if nothing matches (e.g. a "1y" plan vs a "12m" SKU), nothing is marked.
        val activePeriod = (subType as? ProStatus.Active)?.duration

        // get prices from the subscription provider
        val prices = subscriptionCoordinator.getCurrentManager().getSubscriptionPrices()

        val data1Month  = calculatePricesFor(prices.firstOrNull{ it.subscriptionDuration == ProSubscriptionDuration.ONE_MONTH })
        val data3Month  = calculatePricesFor(prices.firstOrNull{ it.subscriptionDuration == ProSubscriptionDuration.THREE_MONTHS })
        val data12Month = calculatePricesFor(prices.firstOrNull{ it.subscriptionDuration == ProSubscriptionDuration.TWELVE_MONTHS })

        // Discount baseline = the highest per-month price among the available plans — i.e. the shortest
        // plan, since shorter plans cost more per month. Don't assume the 1-month SKU exists; whichever
        // plan equals the baseline gets 0% and no badge via discountBadge().
        val baseline = listOfNotNull(data1Month, data3Month, data12Month)
            .maxOfOrNull { it.perMonthUnits } ?: BigDecimal.ZERO

        // One generic card per SKU (longest first). The period label ("3 months"/"1 year") comes from the
        // locale formatter, so a new SKU needs no new strings; the 1-month card naturally carries no
        // discount badge (its per-month price equals the baseline, so the computed discount is 0).
        return listOfNotNull(
            data12Month?.let { buildProPlanCard(ProSubscriptionDuration.TWELVE_MONTHS, it, baseline, subType, activePeriod) },
            data3Month?.let  { buildProPlanCard(ProSubscriptionDuration.THREE_MONTHS,  it, baseline, subType, activePeriod) },
            data1Month?.let  { buildProPlanCard(ProSubscriptionDuration.ONE_MONTH,     it, baseline, subType, activePeriod) },
        )
    }

    /**
     * Build one choose-plan card for a catalog [sku] from its [data] prices. Both card strings are
     * generic over the SKU's (count, unit): the title is "{plan_length} - {monthly_price} / month" and
     * the subtitle "{price} billed every {plan_length}", with `plan_length` rendered by the locale
     * formatter — so no per-duration strings. [baseline] is the 1-month per-month price for the discount
     * badge; a SKU is "current" iff its period equals the active plan's [activePeriod].
     */
    private fun buildProPlanCard(
        sku: ProSubscriptionDuration,
        data: PriceDisplayData,
        baseline: BigDecimal,
        subType: ProStatus,
        activePeriod: ProPlanPeriod?,
    ): ProPlan {
        val isCurrent = activePeriod == sku.period
        val planLength = DateUtils.getLocalisedProPlanLength(context, sku.period)
        return ProPlan(
            title = Phrase.from(
                DateUtils.proStringTemplateOrFallback(
                    context, "proPlanPricePerMonth", "{plan_length} - {monthly_price} / month"
                )
            )
                .put(PLAN_LENGTH_KEY, planLength)
                .put(MONTHLY_PRICE_KEY, data.perMonthText)
                .format().toString(),
            subtitle = Phrase.from(
                DateUtils.proStringTemplateOrFallback(
                    context, "proPlanBilledEvery", "{price} billed every {plan_length}"
                )
            )
                .put(PRICE_KEY, data.totalText)
                .put(PLAN_LENGTH_KEY, planLength)
                .format().toString(),
            // The longest plan is the default selection for a non-subscriber / renew flow.
            selected = isCurrent || (subType !is ProStatus.Active && sku == ProSubscriptionDuration.TWELVE_MONTHS),
            currentPlan = isCurrent,
            durationType = sku,
            badges = buildList {
                if (isCurrent) add(ProPlanBadge(context.getString(R.string.currentBilling)))
                discountBadge(baseline = baseline, perMonthUnits = data.perMonthUnits, showTooltip = isCurrent)?.let(::add)
            },
        )
    }

    private data class PriceDisplayData(val perMonthUnits: BigDecimal, val perMonthText: String, val totalText: String)

    private fun calculatePricesFor(pricing: SubscriptionManager.SubscriptionPricing?): PriceDisplayData? {
        if(pricing == null) return null

        val months = CurrencyFormatter.monthsFromIso(pricing.billingPeriodIso)
        val perMonthUnits = CurrencyFormatter.perMonthUnitsFloor(pricing.priceAmountMicros, months, pricing.priceCurrencyCode)
        val perMonthText  = CurrencyFormatter.formatUnits(perMonthUnits, pricing.priceCurrencyCode)

        val totalUnits = CurrencyFormatter.microToBigDecimal(pricing.priceAmountMicros)
        val totalText = CurrencyFormatter.formatUnits(
            amountUnits = totalUnits,
            currencyCode = pricing.priceCurrencyCode
        )

        return PriceDisplayData(perMonthUnits, perMonthText, totalText)
    }

    private fun discountBadge(baseline: BigDecimal ,perMonthUnits: BigDecimal, showTooltip: Boolean): ProPlanBadge? {
        val pct = CurrencyFormatter.percentOffFloor(baseline, perMonthUnits)
        if (pct <= 0) return null
        val tooltip = if (showTooltip)
            Phrase.from(context.getText(R.string.proDiscountTooltip))
                .put(PERCENT_KEY, pct.toString())
                .format().toString()
        else null
        return ProPlanBadge(
            title = Phrase.from(context.getText(R.string.proPercentOff))
                .put(PERCENT_KEY, pct.toString())
                .format().toString(),
            tooltip = tooltip
        )
    }

    private fun getPlanFromProvider(){
        viewModelScope.launch {
            val selectedPlan = getSelectedPlan() ?: return@launch

            // let the provider handle the plan from their UI
            val providerResult = subscriptionCoordinator.getCurrentManager().purchasePlan(
                selectedPlan.durationType
            )

            // check if we managed to display the plan from the provider
            val data = choosePlanState.value
            if(providerResult.isSuccess && data is State.Success) {
                // show a loader while the user is looking at the UI from the provider
                _choosePlanState.update {
                    State.Success(
                        data.value.copy(purchaseInProgress = true)
                    )
                }
            }
        }
    }

    private fun navigateTo(
        destination: ProSettingsDestination,
        navOptions: NavOptionsBuilder.() -> Unit = {}
    ){
        viewModelScope.launch {
            navigator.navigate(destination, navOptions)
        }
    }

    private fun refreshProStats(){
        viewModelScope.launch {
            // if we have a debug toggle for the loading state, respect it
            val currentDebugState = prefs.getDebugProPlanStatus()
            val debugState = when(currentDebugState) {
                DebugMenuViewModel.DebugProPlanStatus.LOADING -> State.Loading
                DebugMenuViewModel.DebugProPlanStatus.ERROR -> State.Error(Exception())
                else -> null
            }

            // show a loader for the stats
            _proSettingsUIState.update {
                it.copy(
                    proStats = debugState ?: State.Loading
                )
            }

            // calculate pro stats values
            try {
                val stats = withContext(Dispatchers.IO) {
                    val pinsDeferred = async {
                        storage.getTotalPinned()
                    }

                    val badgesDeferred = async {
                        storage.getTotalSentProBadges()
                    }

                    val longMsgDeferred = async {
                        storage.getTotalSentLongMessages()
                    }

                    ProStats(
                        groupsUpdated = 0,
                        pinnedConversations = pinsDeferred.await(),
                        proBadges = badgesDeferred.await(),
                        longMessages = longMsgDeferred.await(),
                    )
                }

                // update ui with results
                _proSettingsUIState.update {
                    it.copy(proStats = debugState ?: State.Success(stats))
                }
            } catch (e: Exception) {
                // currently the UI doesn't have an error display
                // it will look like it's still loading
                // but the logic is there in case we have a look for stats errors
                _proSettingsUIState.update {
                    it.copy(proStats = debugState ?: State.Error(e))
                }
            }
        }
    }

    sealed interface Commands {
        data class ShowOpenUrlDialog(val url: String?) : Commands
        data object ShowTCPolicyDialog: Commands
        data object HideTCPolicyDialog: Commands
        data object HideSimpleDialog : Commands

        data class GoToChoosePlan(val inSheet: Boolean): Commands
        object GoToRefund: Commands
        object GoToCancel: Commands
        object OnPostPlanConfirmation: Commands

        object OpenCancelSubscriptionPage: Commands
        object OnUserBackFromCancellation: Commands

        data class SetShowProBadge(val show: Boolean): Commands

        data class SelectProPlan(val plan: ProPlan): Commands
        data object GetProPlan: Commands
        data object ConfirmProPlan: Commands

        data class OnHeaderClicked(val inSheet: Boolean): Commands
        data object OnProStatsClicked: Commands

        data object RecoverAccount: Commands
    }

    data class ProSettingsState(
        val proDataState: ProDataState = getDefaultSubscriptionStateData(),
        val proStats: State<ProStats> = State.Loading,
        val subscriptionExpiryLabel: CharSequence = "", // eg: "Pro auto renewing in 3 days"
        val subscriptionExpiryDate: CharSequence = "", // eg: "May 21st, 2025"
        val inGracePeriod: Boolean = false
    )

    data class ChoosePlanState(
        val proStatus: ProStatus = ProStatus.NeverSubscribed,
        val hasBillingCapacity: Boolean = false,
        val hasValidSubscription: Boolean = false,  // true is there is a current subscription AND the available subscription manager on this device has an account which matches the product id we got from libsession
        val purchaseInProgress: Boolean = false,
        val plans: List<ProPlan> = emptyList(),
        val enableButton: Boolean = false,
    )

    data class CancelPlanState(
        val proStatus: ProStatus.Active,
        val hasValidSubscription: Boolean,  // true is there is a current subscription AND the available subscription manager on this device has an account which matches the product id we got from libsession
    )

    data class RefundPlanState(
        val proStatus: ProStatus.Active,
        val isQuickRefund: Boolean,
        val quickRefundUrl: String?
    )

    data class ProStats(
        val groupsUpdated: Int = 0,
        val pinnedConversations: Int = 0,
        val proBadges: Int = 0,
        val longMessages: Int = 0
    )

    data class ProPlan(
        val title: String,
        val subtitle: String,
        val durationType: ProSubscriptionDuration,
        val currentPlan: Boolean,
        val selected: Boolean,
        val badges: List<ProPlanBadge>
    )

    data class ProPlanBadge(
        val title: String,
        val tooltip: String? = null
    )

    data class DialogsState(
        val openLinkDialogUrl: String? = null,
        val showTCPolicyDialog: Boolean = false,
        val showSimpleDialog: SimpleDialogData? = null,
    )

    companion object {
        /**
         * Cadence of the #4 grace poll. Shared cross-client contract (spec §9.3) — the same 60s
         * Desktop and iOS use for their while-open poll; keep them in step.
         *
         * **This equals `ProStatusRepository.MIN_UPDATE_INTERVAL_SECONDS`, and that equality is the
         * reason #4 bypasses the freshness floor** rather than being floored like the other routine
         * triggers. A poll running at exactly the floor would have roughly every other tick dropped
         * by timing jitter alone, silently halving the rate. Two files apart the two constants look
         * coincidentally equal; they are not, so change neither without the other.
         */
        private const val GRACE_POLL_INTERVAL_MS = 60_000L
    }
}
