package org.thoughtcrime.securesms.preferences.prosettings

import android.content.Context
import android.content.Intent
import android.icu.util.MeasureUnit
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
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
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.SubscriptionState
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.getDefaultSubscriptionStateData
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.SubscriptionCoordinator
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.pro.subscription.expiryFromNow
import org.thoughtcrime.securesms.ui.SimpleDialogData
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.State
import javax.inject.Inject


@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ProSettingsViewModel.Factory::class)
class ProSettingsViewModel @AssistedInject constructor(
    @Assisted private val navigator: UINavigator<ProSettingsDestination>,
    @param:ApplicationContext private val context: Context,
    private val proStatusManager: ProStatusManager,
    private val subscriptionCoordinator: SubscriptionCoordinator,
    private val dateUtils: DateUtils
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(navigator: UINavigator<ProSettingsDestination>): ProSettingsViewModel
    }

    private val _proSettingsUIState: MutableStateFlow<ProSettingsState> = MutableStateFlow(ProSettingsState())
    val proSettingsUIState: StateFlow<ProSettingsState> = _proSettingsUIState

    private val _dialogState: MutableStateFlow<DialogsState> = MutableStateFlow(DialogsState())
    val dialogState: StateFlow<DialogsState> = _dialogState

    private val _choosePlanState: MutableStateFlow<ChoosePlanState> = MutableStateFlow(ChoosePlanState())
    val choosePlanState: StateFlow<ChoosePlanState> = _choosePlanState

    init {
        // observe subscription status
        viewModelScope.launch {
            proStatusManager.subscriptionState.collect {
               generateState(it)
            }
        }

        // Update choosePlanState whenever proSettingsUIState changes
        viewModelScope.launch {
            _proSettingsUIState
                .map { proState ->
                    val subType = proState.subscriptionState.type
                    val isActive = subType is SubscriptionType.Active
                    val currentPlan12Months = isActive && subType.duration == ProSubscriptionDuration.TWELVE_MONTHS
                    val currentPlan3Months = isActive && subType.duration == ProSubscriptionDuration.THREE_MONTHS
                    val currentPlan1Month = isActive && subType.duration == ProSubscriptionDuration.ONE_MONTH

                    ChoosePlanState(
                        subscriptionType = subType,
                        hasValidSubscription = proState.hasValidSubscription,
                        hasBillingCapacity = proState.hasBillingCapacity,
                        enableButton = subType !is SubscriptionType.Active.AutoRenewing, // only the auto-renew can have a disabled state
                        plans = listOf(
                            ProPlan(
                                title = Phrase.from(context.getText(R.string.proPriceTwelveMonths))
                                    .put(MONTHLY_PRICE_KEY, "$3.99")  //todo PRO calculate properly
                                    .format().toString(),
                                subtitle = Phrase.from(context.getText(R.string.proBilledAnnually))
                                    .put(PRICE_KEY, "$47.99")  //todo PRO calculate properly
                                    .format().toString(),
                                selected = currentPlan12Months || subType !is SubscriptionType.Active, // selected if our active sub is 12 month, or as a default for non pro or renew
                                currentPlan = currentPlan12Months,
                                durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                                badges = buildList {
                                    if(currentPlan12Months){
                                        add(
                                            ProPlanBadge(context.getString(R.string.currentBilling))
                                        )
                                    }

                                    add(
                                        ProPlanBadge(
                                            "33% Off", //todo PRO calculate properly
                                            if(currentPlan12Months)  Phrase.from(context.getText(R.string.proDiscountTooltip))
                                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                                .put(PERCENT_KEY, "33")  //todo PRO calculate properly
                                                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                                                .format().toString()
                                            else null
                                        )
                                    )
                                },
                            ),
                            ProPlan(
                                title = Phrase.from(context.getText(R.string.proPriceThreeMonths))
                                    .put(MONTHLY_PRICE_KEY, "$4.99")  //todo PRO calculate properly
                                    .format().toString(),
                                subtitle = Phrase.from(context.getText(R.string.proBilledQuarterly))
                                    .put(PRICE_KEY, "$14.99")  //todo PRO calculate properly
                                    .format().toString(),
                                selected = currentPlan3Months,
                                currentPlan = currentPlan3Months,
                                durationType = ProSubscriptionDuration.THREE_MONTHS,
                                badges = buildList {
                                    if(currentPlan3Months){
                                        add(
                                            ProPlanBadge(context.getString(R.string.currentBilling))
                                        )
                                    }

                                    add(
                                        ProPlanBadge(
                                            "16% Off", //todo PRO calculate properly
                                            if(currentPlan3Months)  Phrase.from(context.getText(R.string.proDiscountTooltip))
                                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                                .put(PERCENT_KEY, "16")  //todo PRO calculate properly
                                                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                                                .format().toString()
                                            else null
                                        )
                                    )
                                },
                            ),
                            ProPlan(
                                title = Phrase.from(context.getText(R.string.proPriceOneMonth))
                                    .put(MONTHLY_PRICE_KEY, "$5.99") //todo PRO calculate properly
                                    .format().toString(),
                                subtitle = Phrase.from(context.getText(R.string.proBilledMonthly))
                                    .put(PRICE_KEY, "$5") //todo PRO calculate properly
                                    .format().toString(),
                                selected = currentPlan1Month,
                                currentPlan = currentPlan1Month,
                                durationType = ProSubscriptionDuration.ONE_MONTH,
                                badges = if(currentPlan1Month) listOf(
                                    ProPlanBadge(context.getString(R.string.currentBilling))
                                ) else emptyList(),
                            ),
                        )
                    )
                }
                .distinctUntilChanged()
                .collect { newState ->
                    _choosePlanState.update { currentState ->
                        // Preserve the current selection if plans exist
                        if (currentState.plans.isNotEmpty()) {
                            val currentlySelectedPlan = currentState.plans.firstOrNull { it.selected }
                            newState.copy(
                                plans = newState.plans.map { plan ->
                                    plan.copy(
                                        selected = currentlySelectedPlan?.durationType == plan.durationType
                                    )
                                }
                            )
                        } else {
                            newState
                        }
                    }
                }
        }
    }

    private fun generateState(subscriptionState: SubscriptionState){
        //todo PRO need to properly calculate this

        val subType = subscriptionState.type

        _proSettingsUIState.update {
            ProSettingsState(
                subscriptionState = subscriptionState,
                //todo PRO need to get the product id from libsession - also this might be a long running operation
                hasValidSubscription = subscriptionCoordinator.getCurrentManager().hasValidSubscription(""),
                hasBillingCapacity = subscriptionCoordinator.getCurrentManager().supportsBilling,
                subscriptionExpiryLabel = when(subType){
                    is SubscriptionType.Active.AutoRenewing ->
                        Phrase.from(context, R.string.proAutoRenewTime)
                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                            .put(TIME_KEY, dateUtils.getExpiryString(subType.proStatus.validUntil))
                            .format()

                    is SubscriptionType.Active.Expiring ->
                        Phrase.from(context, R.string.proExpiringTime)
                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                            .put(TIME_KEY, dateUtils.getExpiryString(subType.proStatus.validUntil))
                            .format()

                    else -> ""
                },
                subscriptionExpiryDate = when(subType){
                    is SubscriptionType.Active -> subType.duration.expiryFromNow()
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


    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ShowOpenUrlDialog -> {
                _dialogState.update {
                    it.copy(openLinkDialogUrl = command.url)
                }
            }

            Commands.GoToChoosePlan -> {
                when(_proSettingsUIState.value.subscriptionState.refreshState){
                    // if we are in a loading or refresh state we should show a dialog instead
                    is State.Loading -> {
                        val (title, message) = when(_proSettingsUIState.value.subscriptionState.type){
                            is SubscriptionType.Active -> Phrase.from(context.getText(R.string.proAccessLoading))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proAccessLoadingDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            else -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusRenew))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            //todo PRO will need to handle never subscribed here
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
                        val (title, message) = when(_proSettingsUIState.value.subscriptionState.type){
                            is SubscriptionType.Active -> Phrase.from(context.getText(R.string.proAccessError))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proAccessNetworkLoadError))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                                        .format()
                            else -> Phrase.from(context.getText(R.string.proStatusError))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusRenewError))
                                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                                        .format()
                            //todo PRO will need to handle never subscribed here
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
                                    onPositive = { refreshSubscriptionData() },
                                    onNegative = {
                                        onCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT))
                                    }
                                )
                            )
                        }
                    }

                    // otherwise navigate to update or get/renew plan screen
                    else -> navigateTo(
                        if(_proSettingsUIState.value.subscriptionState.type is SubscriptionType.Active )
                            ProSettingsDestination.UpdatePlan
                        else ProSettingsDestination.GetOrRenewPlan
                    )
                }
            }

            Commands.GoToRefund -> {
                navigateTo(ProSettingsDestination.RefundSubscription)
            }

            Commands.GoToCancel -> {
                navigateTo(ProSettingsDestination.CancelSubscription)
            }

            Commands.GoToProSettings -> {
                // navigate back to home and pop all other screens off the stack
                navigateTo(
                    destination = ProSettingsDestination.Home,
                    navOptions = {
                        popUpTo(ProSettingsDestination.Home){
                            inclusive = true
                        }
                    }
                )
            }

            Commands.OpenSubscriptionPage -> {
                val subUrl = (_proSettingsUIState.value.subscriptionState.type as? SubscriptionType.Active)
                    ?.subscriptionDetails?.subscriptionUrl
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

            is Commands.SelectProPlan -> {
                _choosePlanState.update { data ->
                    data.copy(
                        plans = data.plans.map {
                            it.copy(selected = it == command.plan)
                        },
                        enableButton = data.subscriptionType !is SubscriptionType.Active.AutoRenewing
                                || !command.plan.currentPlan
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
                val currentSubscription = _proSettingsUIState.value.subscriptionState.type


                if(currentSubscription is SubscriptionType.Active){
                    val newSubscriptionExpiryString = getSelectedPlan().durationType.expiryFromNow()

                    val currentSubscriptionDuration = DateUtils.getLocalisedTimeDuration(
                        context = context,
                        amount = currentSubscription.duration.duration.months,
                        unit = MeasureUnit.MONTH
                    )

                    val selectedSubscriptionDuration = DateUtils.getLocalisedTimeDuration(
                        context = context,
                        amount = getSelectedPlan().durationType.duration.months,
                        unit = MeasureUnit.MONTH
                    )

                    _dialogState.update {
                        it.copy(
                            showSimpleDialog = SimpleDialogData(
                                title = Phrase.from(context, R.string.updateAccess)
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString(),
                                message = if(currentSubscription is SubscriptionType.Active.AutoRenewing)
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

            Commands.OnHeaderClicked -> {
                when(_proSettingsUIState.value.subscriptionState.refreshState){
                    // if we are in a loading or refresh state we should show a dialog instead
                    is State.Loading -> {
                        val (title, message) = when(_proSettingsUIState.value.subscriptionState.type){
                            is SubscriptionType.Active -> Phrase.from(context.getText(R.string.proStatusLoading))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.proStatusLoadingDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            else -> Phrase.from(context.getText(R.string.checkingProStatus))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString() to
                                    Phrase.from(context.getText(R.string.checkingProStatusDescription))
                                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                        .format()
                            //todo PRO will need to handle never subscribed here
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
                            val (title, message) = when(_proSettingsUIState.value.subscriptionState.type){
                                is SubscriptionType.Active -> Phrase.from(context.getText(R.string.proStatusError))
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusRefreshNetworkError))
                                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                            .format()
                                else -> Phrase.from(context.getText(R.string.proStatusError))
                                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                    .format().toString() to
                                        Phrase.from(context.getText(R.string.proStatusRefreshNetworkError))
                                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                            .format()
                                //todo PRO will need to handle never subscribed here
                            }

                            it.copy(
                                showSimpleDialog = SimpleDialogData(
                                    title = title,
                                    message = message,
                                    positiveText = context.getString(R.string.retry),
                                    negativeText = context.getString(R.string.helpSupport),
                                    positiveStyleDanger = false,
                                    showXIcon = true,
                                    onPositive = { refreshSubscriptionData() },
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

    private fun refreshSubscriptionData(){
        //todo PRO implement properly
    }

    private fun getSelectedPlan(): ProPlan {
        return _choosePlanState.value.plans.first { it.selected }
    }

    private fun getPlanFromProvider(){
        subscriptionCoordinator.getCurrentManager().purchasePlan(
            getSelectedPlan().durationType
        )
    }

    fun getSubscriptionManager(): SubscriptionManager {
        return subscriptionCoordinator.getCurrentManager()
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

        object GoToChoosePlan: Commands
        object GoToRefund: Commands
        object GoToCancel: Commands
        object GoToProSettings: Commands

        object OpenSubscriptionPage: Commands

        data class SetShowProBadge(val show: Boolean): Commands

        data class SelectProPlan(val plan: ProPlan): Commands
        data object GetProPlan: Commands
        data object ConfirmProPlan: Commands

        data object OnHeaderClicked: Commands
        data object OnProStatsClicked: Commands
    }

    data class ProSettingsState(
        val subscriptionState: SubscriptionState = getDefaultSubscriptionStateData(),
        val proStats: State<ProStats> = State.Loading,
        val hasBillingCapacity: Boolean = false, // true is the current build flavour supports billing
        val hasValidSubscription: Boolean = false, // true is there is a current subscription AND the available subscription manager on this device has an account which matches the product id we got from libsession
        val subscriptionExpiryLabel: CharSequence = "", // eg: "Pro auto renewing in 3 days"
        val subscriptionExpiryDate: CharSequence = "" // eg: "May 21st, 2025"
    )

    data class ChoosePlanState(
        val subscriptionType: SubscriptionType = SubscriptionType.NeverSubscribed,
        val hasBillingCapacity: Boolean = false,
        val hasValidSubscription: Boolean = false,
        val plans: List<ProPlan> = emptyList(),
        val enableButton: Boolean = false,
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
