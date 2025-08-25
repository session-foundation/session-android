package org.thoughtcrime.securesms.tokenpage

import org.session.libsession.utilities.NonTranslatableStringConstants
import java.math.BigDecimal

// Data class to hold a collection of variables used in our UI state
data class TokenPageUIState(

    // true during 'pull to refresh'
    val isRefreshing: Boolean = false,

    // Details for how many nodes are in our swarm, and how many nodes are securing our messages.
    // See: `TokenPageViewModel.populateNodeData()` for calculation details.
    val currentSessionNodesInSwarm: Int = 0,
    val currentSessionNodesSecuringMessages: Int = 0,

    // info response data
    val infoResponseData: InfoResponseStateData? = null,

    // When we get a new InfoResponse we update the session node counts - and during the refresh we
    // show the loading animation rather than the nodes in swarm & securing-messages values.
    val showNodeCountsAsRefreshing: Boolean = false,

    // ----- PriceResponse / PriceData UI representations -----

    // Number so we can perform calculation (this value is obtained from PriceData.usd)
    var currentSentPriceUSDString: String = "",

    // Number so we can perform calculations (this value is obtained from PriceData.usd_market_cap)
    val currentMarketCapUSDString: String = "",

    // ----- TokenResponse / TokenData UI representations -----

    // At the time of the token-generation event this is 40 million (this value is obtained from TokenData.staking_reward_pool)
    val currentStakingRewardPool: SerializableBigDecimal = SerializableBigDecimal(0),
    val currentStakingRewardPoolString: String = "",

    // The amount of SENT securing the network is the total number of nodes multiplied by the staking requirement per node..
    val networkSecuredBySENTString: String = "",

    // ..and the total amount of USD securing the network is the SENT count multiplied by the current token price.
    val networkSecuredByUSDString: String = "\$- ${NonTranslatableStringConstants.USD_NAME_SHORT}",

    val priceDataPopupText: String = "",

    // string for the tooltip
    val lastUpdatedString: String = "",
)

data class InfoResponseStateData(
    val tokenContractAddress: String,
    val canCopyTokenContractAddress: Boolean,
)