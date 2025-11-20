package org.thoughtcrime.securesms.preferences.prosettings

import android.content.Context
import android.content.Intent
import android.icu.util.MeasureUnit
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavOptionsBuilder
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.ACTION_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.MONTHLY_PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PERCENT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SELECTED_PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SELECTED_PLAN_LENGTH_SINGULAR_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProDataState
import org.thoughtcrime.securesms.pro.ProDetailsRepository
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.getDefaultSubscriptionStateData
import org.thoughtcrime.securesms.pro.isFromAnotherPlatform
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionCoordinator
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.pro.subscription.expiryFromNow
import org.thoughtcrime.securesms.ui.SimpleDialogData
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.CurrencyFormatter
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.State
import java.math.BigDecimal


@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ProSettingsViewModel.Factory::class)
class ProSettingsViewModel @AssistedInject constructor(
    @Assisted private val navigator: UINavigator<ProSettingsDestination>,
    @param:ApplicationContext private val context: Context,
    private val proStatusManager: ProStatusManager,
    private val subscriptionCoordinator: SubscriptionCoordinator,
    private val dateUtils: DateUtils,
    private val prefs: TextSecurePreferences,
    private val proDetailsRepository: ProDetailsRepository,
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

    init {
        // observe subscription status
        viewModelScope.launch {
            proStatusManager.proDataState.collect {
               generateState(it)
            }
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

                    is SubscriptionManager.PurchaseEvent.Failed.ServerError -> {
                        // this is a special case of failure. We should display a custom dialog and allow the user to retry
                        _dialogState.update {
                            val action = context.getString(
                                when(_proSettingsUIState.value.proDataState.type) {
                                    is ProStatus.Active -> R.string.proUpdatingAction
                                    is ProStatus.Expired -> R.string.proRenewingAction
                                    else -> R.string.proUpgradingAction
                                }
                            )

                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = context.getString(R.string.paymentError),
                                    message = Phrase.from(context, R.string.paymentProError)
                                        .put(ACTION_TYPE_KEY, action)
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format(),
                                    positiveText = context.getString(R.string.retry),
                                    negativeText = context.getString(R.string.helpSupport),
                                    positiveStyleDanger = false,
                                    showXIcon = true,
                                    onPositive = {
                                        // show the loader again
                                        val data = choosePlanState.value
                                        if(data is State.Success) {
                                            _choosePlanState.update {
                                                State.Success(
                                                    data.value.copy(purchaseInProgress = true)
                                                )
                                            }
                                        }

                                        // retry the post purchase code
                                        subscriptionCoordinator.getCurrentManager().onPurchaseSuccessful(
                                            orderId = purchaseEvent.orderId,
                                            paymentId = purchaseEvent.paymentId
                                        )
                                    },
                                    onNegative = {
                                        onCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT))
                                    }
                                )
                            )
                        }
                    }

                    is SubscriptionManager.PurchaseEvent.Cancelled -> {
                        // nothing to do in this case
                    }
                }
            }
        }
    }

    private fun generateState(proDataState: ProDataState){
        //todo PRO need to properly calculate this

        val subType = proDataState.type

        _proSettingsUIState.update {
            ProSettingsState(
                proDataState = proDataState,
                subscriptionExpiryLabel = when(subType){
                    is ProStatus.Active.AutoRenewing ->
                        Phrase.from(context, R.string.proAutoRenewTime)
                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                            .put(TIME_KEY, dateUtils.getExpiryString(subType.validUntil))
                            .format()

                    is ProStatus.Active.Expiring ->
                        Phrase.from(context, R.string.proExpiringTime)
                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                            .put(TIME_KEY, dateUtils.getExpiryString(subType.validUntil))
                            .format()

                    else -> ""
                },
                subscriptionExpiryDate = when(subType){
                    is ProStatus.Active -> subType.duration.expiryFromNow()
                    else -> ""
                },
                proStats = State.Success( //todo PRO calculate properly
                    ProStats(
                        groupsUpdated = 0,
                        pinnedConversations = 12,
                        proBadges = 6400,
                        longMessages = 215,
                    )
                )
            )
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
                val isQuickRefund = if(prefs.getDebugIsWithinQuickRefund() && prefs.forceCurrentUserAsPro()) true // debug mode
                else sub.isWithinQuickRefundWindow()

                State.Success(
                    RefundPlanState(
                        proStatus = sub,
                        isQuickRefund = isQuickRefund,
                        quickRefundUrl = sub.providerData.refundUrl
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
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proAccessLoadingDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            state is ProStatus.NeverSubscribed
                                    || command.inSheet -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusContinue))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            else -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusRenew))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
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
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proAccessNetworkLoadError))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                                        .format()
                            state is ProStatus.NeverSubscribed
                                    || command.inSheet-> Phrase.from(context.getText(R.string.proStatusError))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusNetworkErrorContinue))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            else -> Phrase.from(context.getText(R.string.proStatusError))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusRenewError))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
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
                                    onPositive = { refreshProDetails() },
                                    onNegative = {
                                        onCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT))
                                    }
                                )
                            )
                        }
                    }

                    // go to the "choose plan" screen
                    else -> goToChoosePlan()
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
                //todo PRO implement
            }

            is Commands.RefeshProDetails -> {
                refreshProDetails()
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
                    val newSubscriptionExpiryString = selectedPlan.durationType.expiryFromNow()

                    val currentSubscriptionDuration = DateUtils.getLocalisedTimeDuration(
                        context = context,
                        amount = currentSubscription.duration.duration.months,
                        unit = MeasureUnit.MONTH
                    )

                    val selectedSubscriptionDuration = DateUtils.getLocalisedTimeDuration(
                        context = context,
                        amount = selectedPlan.durationType.duration.months,
                        unit = MeasureUnit.MONTH
                    )

                    _dialogState.update {
                        it.copy(
                            showSimpleDialog = SimpleDialogData(
                                title = Phrase.from(context, R.string.updateAccess)
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString(),
                                message = if(currentSubscription is ProStatus.Active.AutoRenewing)
                                    Phrase.from(context.getText(R.string.proUpdateAccessDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .put(DATE_KEY, newSubscriptionExpiryString)
                                        .put(CURRENT_PLAN_LENGTH_KEY, currentSubscriptionDuration)
                                        .put(SELECTED_PLAN_LENGTH_KEY, selectedSubscriptionDuration)
                                        // for this string below, we want to remove the 's' at the end if there is one: 12 Months becomes 12 Month
                                        .put(SELECTED_PLAN_LENGTH_SINGULAR_KEY, selectedSubscriptionDuration.removeSuffix("s"))
                                        .format()
                                else Phrase.from(context.getText(R.string.proUpdateAccessExpireDescription))
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .put(DATE_KEY, newSubscriptionExpiryString)
                                    .put(SELECTED_PLAN_LENGTH_KEY, selectedSubscriptionDuration)
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
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusLoadingDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            state is ProStatus.NeverSubscribed
                                    || command.inSheet-> Phrase.from(context.getText(R.string.checkingProStatus))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusContinue))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            else -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
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
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusRefreshNetworkError))
                                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                            .format()
                                state is ProStatus.NeverSubscribed ||
                                     command.inSheet -> Phrase.from(context.getText(R.string.proStatusError))
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusNetworkErrorContinue))
                                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                            .format()
                                else -> Phrase.from(context.getText(R.string.proStatusError))
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusRefreshNetworkError))
                                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
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
                                    onPositive = { refreshProDetails() },
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
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format().toString(),
                                    message = Phrase.from(context.getText(R.string.proStatsLoadingDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
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

    private fun refreshProDetails(){
        // refreshes the pro details data
        proDetailsRepository.requestRefresh()
    }

    private fun getSelectedPlan(): ProPlan? {
        return (_choosePlanState.value as? State.Success)?.value?.plans?.first { it.selected }
    }

    private fun goToChoosePlan(){
        // Navigate to choose plan screen
        navigateTo(ProSettingsDestination.ChoosePlan)
    }

    private suspend fun getSubscriptionPlans(subType: ProStatus): List<ProPlan> {
        val isActive = subType is ProStatus.Active
        val currentPlan12Months = isActive && subType.duration == ProSubscriptionDuration.TWELVE_MONTHS
        val currentPlan3Months = isActive && subType.duration == ProSubscriptionDuration.THREE_MONTHS
        val currentPlan1Month = isActive && subType.duration == ProSubscriptionDuration.ONE_MONTH

        // get prices from the subscription provider
        val prices = subscriptionCoordinator.getCurrentManager().getSubscriptionPrices()

        val data1Month  = calculatePricesFor(prices.firstOrNull{ it.subscriptionDuration == ProSubscriptionDuration.ONE_MONTH })
        val data3Month  = calculatePricesFor(prices.firstOrNull{ it.subscriptionDuration == ProSubscriptionDuration.THREE_MONTHS })
        val data12Month = calculatePricesFor(prices.firstOrNull{ it.subscriptionDuration == ProSubscriptionDuration.TWELVE_MONTHS })

        val baseline = data1Month?.perMonthUnits ?: BigDecimal.ZERO

        val plan12Months = data12Month?.let {
            ProPlan(
                title = Phrase.from(context.getText(R.string.proPriceTwelveMonths))
                    .put(MONTHLY_PRICE_KEY, it.perMonthText)
                    .format().toString(),
                subtitle = Phrase.from(context.getText(R.string.proBilledAnnually))
                    .put(PRICE_KEY, it.totalText)
                    .format().toString(),
                selected = currentPlan12Months || subType !is ProStatus.Active, // selected if our active sub is 12 month, or as a default for non pro or renew
                currentPlan = currentPlan12Months,
                durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                badges = buildList {
                    if (currentPlan12Months) add(ProPlanBadge(context.getString(R.string.currentBilling)))
                    discountBadge(baseline = baseline, it.perMonthUnits, showTooltip = currentPlan12Months)?.let(this::add)
                }
            )
        }

        val plan3Months = data3Month?.let {
            ProPlan(
                title = Phrase.from(context.getText(R.string.proPriceThreeMonths))
                    .put(MONTHLY_PRICE_KEY, it.perMonthText)
                    .format().toString(),
                subtitle = Phrase.from(context.getText(R.string.proBilledQuarterly))
                    .put(PRICE_KEY, it.totalText)
                    .format().toString(),
                selected = currentPlan3Months,
                currentPlan = currentPlan3Months,
                durationType = ProSubscriptionDuration.THREE_MONTHS,
                badges = buildList {
                    if (currentPlan3Months) add(ProPlanBadge(context.getString(R.string.currentBilling)))
                    discountBadge(baseline = baseline, it.perMonthUnits, showTooltip = currentPlan3Months)?.let(this::add)
                }
            )
        }

        val plan1Month = data1Month?.let {
            ProPlan(
                title = Phrase.from(context.getText(R.string.proPriceOneMonth))
                    .put(MONTHLY_PRICE_KEY, it.perMonthText)
                    .format().toString(),
                subtitle = Phrase.from(context.getText(R.string.proBilledMonthly))
                    .put(PRICE_KEY, it.totalText)
                    .format().toString(),
                selected = currentPlan1Month,
                currentPlan = currentPlan1Month,
                durationType = ProSubscriptionDuration.ONE_MONTH,
                badges = if (currentPlan1Month) listOf(ProPlanBadge(context.getString(R.string.currentBilling))) else emptyList()
                // no discount on the baseline 1 month...
            )
        }

        return listOfNotNull(plan12Months, plan3Months, plan1Month)
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
                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                .put(PERCENT_KEY, pct.toString())
                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
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

        data class SetShowProBadge(val show: Boolean): Commands

        data class SelectProPlan(val plan: ProPlan): Commands
        data object GetProPlan: Commands
        data object ConfirmProPlan: Commands

        data class OnHeaderClicked(val inSheet: Boolean): Commands
        data object OnProStatsClicked: Commands

        data object RefeshProDetails: Commands
    }

    data class ProSettingsState(
        val proDataState: ProDataState = getDefaultSubscriptionStateData(),
        val proStats: State<ProStats> = State.Loading,
        val subscriptionExpiryLabel: CharSequence = "", // eg: "Pro auto renewing in 3 days"
        val subscriptionExpiryDate: CharSequence = "", // eg: "May 21st, 2025"
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
}
