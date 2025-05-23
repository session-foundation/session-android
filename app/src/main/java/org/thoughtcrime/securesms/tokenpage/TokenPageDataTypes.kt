package org.thoughtcrime.securesms.tokenpage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.thoughtcrime.securesms.util.NumberUtil.formatWithDecimalPlaces
import java.math.BigDecimal

/***
 * IMPORTANT: THe server gives us timestamps in SECONDS rather than milliseconds (and expects any
 * timestamps we provide to be in seconds) - so if we want to compare a server timestamp to now (e.g,
 * `System.currentTimeMillis()`) then we must first either multiply the server's timestamp by 1000,
 * or use our millisecond timestamp `inWholeSeconds` to get everything aligned.
 */

// Note: We use BigDecimal in these classes for numeric values so that there are no rounding errors
// and we can guarantee 9 decimals of precision at all times.
// Also: We can't serialize BigDecimals natively so I've wrapped it (see BigDecimalSerializer.kt)
typealias SerializableBigDecimal = @Serializable(with = BigDecimalSerializer::class) BigDecimal

// Data class to hold details provided by the `GET /info` endpoint.
@Serializable
data class InfoResponse(
    @SerialName("t")       val infoResponseTimestampSecs: Long,
    @SerialName("price")   val priceData: PriceData,
    @SerialName("token")   val tokenData: TokenData,
    @SerialName("network") val networkData: NetworkData
)

// Data class to wrap up details regarding the current SENT token price
@Serializable
data class PriceData(
    // The token price in US dollars
    @SerialName("usd") val tokenPriceUSD: SerializableBigDecimal,

    // Current market cap value in US dollars
    @SerialName("usd_market_cap") val marketCapUSD: SerializableBigDecimal,

    // The timestamp (in seconds) of when the server's CoinGecko-sourced token price data was last updated
    @SerialName("t_price") val priceTimestampSecs: Long,

    // The timestamp (in seconds) of when the server's CoinGecko-sourced token price data will be
    // considered stale and we'll allow the user to poll the server for fresh data. The server only
    // polls CoinGecko once every 5 mins - so what we can do on the client side is check if the
    // current time is lower than `t_stale`, and if it is then we don't poll the server again as
    // we'd just be getting the same data.
    @SerialName("t_stale") val staleTimestampSecs: Long
) {
    // Get the token price in USD to 2 decimal places in a locale-aware manner
    fun getLocaleFormattedTokenPriceString(): String {
        return "\$" + tokenPriceUSD.formatWithDecimalPlaces(2) + " USD"
    }

    // Get the token price in USD to ZERO decimal places in a locale-aware manner
    fun getLocaleFormattedMarketCapString(): String {
        return "\$" + marketCapUSD.formatWithDecimalPlaces( 0) + " USD"
    }
}

// Data class to hold details provided in a InfoResponse or via the `GET /token` endpoint
@Serializable
data class TokenData(
    // How many tokens must be staked to run a Session Node
    @SerialName("staking_requirement") val nodeStakingRequirement: SerializableBigDecimal,

    // The number of tokens currently in the staking reward pool. While this value starts
    // at 40,000,000 it will decrease as tokens are handed out as rewards, and will
    // increase when we (Session) top up the pool.
    @SerialName("staking_reward_pool") val stakingRewardPool: SerializableBigDecimal,

    // The ethereum contract address for the SENT token. This is 42 chars in length - being
    // "0x" followed by 40 hexadecimal chars.
    @SerialName("contract_address") val tokenContractAddress: String
) {
    // Get staking reward pool in a locale-aware manner to ZERO decimal places (while the reward pool may not be a
    // a whole number, we don't care about fractions when the staking pool is in the range of millions of tokens).
    fun getLocaleFormattedStakingRewardPool(): String {
        return stakingRewardPool.formatWithDecimalPlaces(0)
    }
}

// Small data class included as part of an InfoResponse
@Serializable
data class NetworkData(
    @SerialName("network_size") val networkSize: Int,
    @SerialName("network_staked_tokens") val networkTokens: SerializableBigDecimal,
    @SerialName("network_staked_usd") val networkUSD: SerializableBigDecimal,
)
