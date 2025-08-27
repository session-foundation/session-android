package org.thoughtcrime.securesms.tokenpage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import nl.komponents.kovenant.Promise
import org.session.libsession.LocalisedTimeUtil.toShortSinglePartString
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.NonTranslatableStringConstants.SESSION_NETWORK_DATA_PRICE
import org.session.libsession.utilities.NonTranslatableStringConstants.TOKEN_NAME_SHORT
import org.session.libsession.utilities.NonTranslatableStringConstants.USD_NAME_SHORT
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_TIME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.RELATIVE_TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.NetworkConnectivity
import org.thoughtcrime.securesms.util.NumberUtil.formatAbbreviated
import org.thoughtcrime.securesms.util.NumberUtil.formatWithDecimalPlaces
import javax.inject.Inject
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@HiltViewModel
class TokenPageViewModel @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val tokenDataManager: TokenDataManager,
    private val dateUtils: DateUtils,
    private val prefs: TextSecurePreferences,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val TAG = "TokenPageVM"

    @Inject
    lateinit var internetConnectivity: NetworkConnectivity

    private val _uiState: MutableStateFlow<TokenPageUIState> = MutableStateFlow(TokenPageUIState())
    val uiState: StateFlow<TokenPageUIState> = _uiState.asStateFlow()

    private val infoResponse: InfoResponse?
        get() = tokenDataManager.getInfoResponse()

    private val unavailableString = context.getString(R.string.unavailable)

    init {
        // grab info data from manager
        viewModelScope.launch {
            tokenDataManager.infoResponse.collect { infoResponseResult ->
                when(infoResponseResult){
                    is TokenDataManager.InfoResponseState.Loading -> showLoading()

                    is TokenDataManager.InfoResponseState.Data -> handleInfoResponse(infoResponseResult.data)

                    is TokenDataManager.InfoResponseState.Failure -> resetDisplayedValuesToDefault()
                }
            }
        }

        // on launch of the token page, check if we have new info data, and acquire node data
        viewModelScope.launch {
            // get node data
            getNodeData()

            tokenDataManager.fetchInfoDataIfNeeded()
        }

        // update label when update time changes
        viewModelScope.launch {
            tokenDataManager.lastUpdateTimeMillis.collect {
                updateLastUpdatedText()
            }
        }

        startLastUpdateTimer()
    }

    private fun startLastUpdateTimer() {
        viewModelScope.launch {
            while (true) {
                updateLastUpdatedText()
                delay(1.minutes)
            }
        }
    }

    private fun updateLastUpdatedText() {
        val currentTimeMillis = System.currentTimeMillis()
        val durationSinceLastUpdate = (currentTimeMillis - tokenDataManager.getLastUpdateTimeMillis()).milliseconds
        val shortLastUpdateString = durationSinceLastUpdate.toShortSinglePartString()

        val lastUpdatedTxt = Phrase.from(context, R.string.updated)
            .put(RELATIVE_TIME_KEY, shortLastUpdateString)
            .format().toString()

        // Update only the lastUpdatedString field in the UI state
        _uiState.update { currentState ->
            currentState.copy(lastUpdatedString = lastUpdatedTxt)
        }
    }

    // Handler to instigate certain actions when we receive a TokenPageCommand
    fun onCommand(command: TokenPageCommand) {

        when (command) {
            is TokenPageCommand.RefreshData -> refreshData()
        }
    }

    private fun showLoading() {
        _uiState.update { state ->
            val loadingString = context.getString(R.string.loading)
            state.copy(
                showNodeCountsAsRefreshing = true,
                currentSentPriceUSDString = loadingString,
                currentMarketCapUSDString = loadingString,
                currentStakingRewardPoolString = loadingString,
                networkSecuredBySENTString = loadingString,
                networkSecuredByUSDString = "\$- ${USD_NAME_SHORT}"
            )
        }
    }

    private fun handleInfoResponse(infoResponse: InfoResponse?) {
        // update the rest of the UI with details like token price, market cap etc.
        if (infoResponse != null) {
            // Calculate price data time text
            val priceTimeMS =
                infoResponse.priceData.priceTimestampSecs * 1000L // Multiply by 1000 to get timestamp in milliseconds

            // Note: If we do not have data then `lastPriceUpdateDate" will be "-" and `lastPriceUpdateTime` will be ""
            val priceDataText = Phrase.from(SESSION_NETWORK_DATA_PRICE)
                .put(DATE_TIME_KEY, dateUtils.getLocaleFormattedDateTime(priceTimeMS))
                .format().toString()

            _uiState.update { state ->
                state.copy(
                    infoResponseData = InfoResponseStateData(
                        tokenContractAddress = infoResponse.tokenData.tokenContractAddress,
                        canCopyTokenContractAddress = infoResponse.tokenData.tokenContractAddress.isNotEmpty(),
                    ),

                    priceDataPopupText = priceDataText,

                    showNodeCountsAsRefreshing = false,

                    currentSentPriceUSDString = "\$" + infoResponse.priceData.tokenPriceUSD.formatWithDecimalPlaces(2) + " $USD_NAME_SHORT", // Formatted token price value "$1.23 USD" etc.
                    currentMarketCapUSDString = if(infoResponse.priceData.marketCapUSD ==  null) unavailableString
                        else "\$" + infoResponse.priceData.marketCapUSD.formatWithDecimalPlaces( 0) + " $USD_NAME_SHORT",  // Formatted market cap value "$1,234,567 USD" etc.
                    
                    currentStakingRewardPool = infoResponse.tokenData.stakingRewardPool,
                    currentStakingRewardPoolString = infoResponse.tokenData.getLocaleFormattedStakingRewardPool() + " $TOKEN_NAME_SHORT",

                    currentSessionNodesSecuringMessages = min(infoResponse.networkData.networkSize, state.currentSessionNodesSecuringMessages), // we now apply the 'min' from the formula defined in getNodeData
                    networkSecuredBySENTString = infoResponse.networkData.networkTokens
                        .formatAbbreviated(
                            minFractionDigits = 0,
                            maxFractionDigits = 0
                        ) + " " + TOKEN_NAME_SHORT,

                    networkSecuredByUSDString = "\$" + infoResponse.networkData.networkUSD
                        .formatWithDecimalPlaces(0) + " ${USD_NAME_SHORT}"
                )
            }
        } else {
            Log.w(TAG, "Received null InfoResponse - unable to proceed.")
            resetDisplayedValuesToDefault()
        }
    }

    // sets the data back to its default, likely due to a null info response
    private fun resetDisplayedValuesToDefault() {
        _uiState.update { state ->
            state.copy(
                showNodeCountsAsRefreshing = true,
                currentSentPriceUSDString = unavailableString,
                currentMarketCapUSDString = unavailableString,
                currentStakingRewardPoolString = unavailableString,
                networkSecuredBySENTString = unavailableString,
                networkSecuredByUSDString = "\$- ${USD_NAME_SHORT}",
                infoResponseData = null,
                priceDataPopupText = Phrase.from(SESSION_NETWORK_DATA_PRICE)
                    .put(DATE_TIME_KEY, "-")
                    .format().toString()
            )
        }
    }

    private fun refreshData() {
        _uiState.update {
            it.copy(isRefreshing = true)
        }

        viewModelScope.launch {
            // if the data isn't stale then we don't need to refresh it, instead we fake a small wait
            try {
                if (!tokenDataManager.fetchInfoDataIfNeeded()) {
                    // If there is no fresh server data then we'll update the UI elements to show their loading
                    // state for half a second then put them back as they were.
                    showLoading()
                    delay(timeMillis = 500)
                    handleInfoResponse(infoResponse)
                }
            } catch (e: Exception){ /* exception can be ignored here as the infoResponse can return a wrapped failure object */ }

            // Reset the refreshing state when done
            delay(100) // it seems there's a bug in compose where the refresh does not go away if hidden too quickly
            _uiState.update { state ->
                state.copy(
                    isRefreshing = false
                )
            }
        }
    }

    // Method to populate both the number of nodes in our swarm and the number of nodes protecting our messages.
    // Note: We pass this in to the token page so we can call it when we refresh the page.
    private suspend fun getNodeData() {
        withContext(Dispatchers.Default) {
            val myPublicKey = prefs.getLocalNumber() ?: return@withContext

            val getSwarmSetPromise: Promise<Set<Snode>, Exception> =
                SnodeAPI.getSwarm(myPublicKey)

            val numSessionNodesInOurSwarm = try {
                // Get the count of Session nodes in our swarm (technically in the range 1..10, but
                // even a new account seems to start with a nodes-in-swarm count of 4).
                getSwarmSetPromise.await().size
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't get nodes in swarm count.", e)
                5 // Pick a sane middle-ground should we error for any reason
            }

            // 2.) Session nodes protecting our messages
            var num1to1Convos = 0
            var numLegacyGroupConvos = 0
            var numGroupV2Convos = 0

            // Grab the database and reader details we need to count the conversations / groups
            val convoList = conversationRepository.observeConversationList().first()
            val result = mutableSetOf<Recipient>()

            // Look through the database to build up our conversation & group counts (still on Dispatchers.IO not the main thread)
            convoList.forEach { thread ->
                val recipient = thread.recipient
                result.add(recipient)

                if (recipient.is1on1) {
                    num1to1Convos += 1
                } else if (recipient.isGroupV2Recipient) {
                    numGroupV2Convos += 1
                } else if (recipient.isLegacyGroupRecipient) {
                    numLegacyGroupConvos += 1
                }
            }

            // This is hard-coded to 2 on Android but may vary on other platforms
            val pathCount = OnionRequestAPI.paths.value.size

            /*
            Note: Num session nodes securing you messages formula is:
            min(
                total_service_node_cache_size, << this part comes from the networkData in infoResponse: networkSize
                (
                    num_swarm_nodes +
                    (num_paths * 3) +
                    (
                        (num_1_to_1_convos       * 6) +
                        (num_legacy_group_convos * 6) +
                        (num_group_v2_convos     * 6)
                    )
                )
            )
            */
            var nodeFormula = numSessionNodesInOurSwarm +
                    (pathCount * 3) +
                    (num1to1Convos * 6) +
                    (numLegacyGroupConvos * 6) +
                    (numGroupV2Convos * 6)

            // if we already have some server data though, we should apply the cap
            // if not this cap will be applied once we get the server data
            if(infoResponse?.networkData?.networkSize != null){
                nodeFormula = min(infoResponse!!.networkData.networkSize, nodeFormula)
            }

            _uiState.update { state ->
                state.copy(
                    currentSessionNodesInSwarm = numSessionNodesInOurSwarm,
                    currentSessionNodesSecuringMessages = nodeFormula
                )
            }
        }
    }

}