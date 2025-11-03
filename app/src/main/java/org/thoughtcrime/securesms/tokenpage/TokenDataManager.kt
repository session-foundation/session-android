package org.thoughtcrime.securesms.tokenpage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenDataManager @Inject constructor(
    private val textSecurePreferences: TextSecurePreferences,
    private val tokenRepository: TokenRepository,
    @param:ManagerScope private val scope: CoroutineScope
) : OnAppStartupComponent {
    private val TAG = "TokenDataManager"

    // Cached infoResponse in memory
    private val _infoResponse = MutableStateFlow<InfoResponseState>(InfoResponseState.Loading)
    val infoResponse: StateFlow<InfoResponseState> get() = _infoResponse

    // Store the reference update time separately from UI state
    private var _lastUpdateTimeMillis: MutableStateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
    val lastUpdateTimeMillis: StateFlow<Long> get() = _lastUpdateTimeMillis

    // Even if the server responds back to us faster than this we'll wait until at least a total
    // duration of this value milliseconds has elapsed before updating the UI - otherwise it looks
    // jank when the UI switches to "Loading..." and then near-instantly updates to the given values.
    private val MINIMUM_SERVER_RESPONSE_DELAY_MS = 500L

    override fun onPostAppStarted() {
        // we want to preload the data as soon as the user is logged in
        scope.launch {
            textSecurePreferences.watchLocalNumber()
                .map { it != null }
                .distinctUntilChanged()
                .collect { logged ->
                    if(logged) fetchInfoResponse()
                }
        }
    }

    fun getLastUpdateTimeMillis() = _lastUpdateTimeMillis.value


    /**
     * Fetches the InfoResponse from the tokenRepository, delays if needed,
     * and then updates the MutableStateFlow.
     *
     * @return The fetched InfoResponse, or null if there was an error.
     */
    private suspend fun fetchInfoResponse() {
        _infoResponse.value = InfoResponseState.Loading

        val requestStartTimestamp = System.currentTimeMillis()
        return try {
            // Fetch the InfoResponse on an IO dispatcher
            val response = withContext(Dispatchers.IO) {
                tokenRepository.getInfoResponse()
            }
            // Ensure the minimum delay to avoid janky UI updates
            forceWaitAtLeast500ms(requestStartTimestamp)
            // Update the state flow so observers can react
            _infoResponse.value = if(response != null )
                InfoResponseState.Data(response)
            else InfoResponseState.Failure(Exception("InfoResponse was null"))

            updateLastUpdateTimeMillis()
            Log.w(TAG, "Fetched infoResponse: $response")
        } catch (e: Exception) {
            Log.w(TAG, "InfoResponse error: $e")
            _infoResponse.value =InfoResponseState.Failure(e)
        }
    }

    fun updateLastUpdateTimeMillis() {
        _lastUpdateTimeMillis.value = System.currentTimeMillis()
    }


    // Method to ensure we wait for at least the `MINIMUM_SERVER_RESPONSE_DELAY_MS` milliseconds
    // when requesting data from the server so that the UI doesn't blink to "Loading..." and then
    // near-instantly back to the correct values.
    private suspend fun forceWaitAtLeast500ms(requestStartTimestampMS: Long) {
        val requestEndTimestamp = System.currentTimeMillis()
        val requestDuration = requestEndTimestamp - requestStartTimestampMS
        if (requestDuration < MINIMUM_SERVER_RESPONSE_DELAY_MS) {
            val fillerDelayMS = MINIMUM_SERVER_RESPONSE_DELAY_MS - requestDuration
            delay(fillerDelayMS)
        }
    }

    /**
     * Fetches the info data if it's considered stale.
     *
     * This function checks if the current data is stale using [dataIsStale]. If the data is
     * stale, it fetches new data using [fetchInfoResponse] and returns `true`. Otherwise, it
     * logs that the data is not stale and returns `false`.
     *
     * @return `true` if new data was fetched, `false` otherwise.
     */
    suspend fun fetchInfoDataIfNeeded(): Boolean{
        return if(dataIsStale()) {
            Log.i(TAG, "Data is stale, fetch new data")
            fetchInfoResponse()
            true
        } else{
            Log.i(TAG, "Data is not stale...")
            updateLastUpdateTimeMillis()
            false
        }
    }

    // If the server data is considered stale then 'timestamp minus now' is negative and we should refresh data from the server
    private fun dataIsStale() = if (getInfoResponse() != null) {
        val nowInSeconds = System.currentTimeMillis() / 1000L

        // If this value is negative it means that the server should have fresh data we can grab
        val secondsUntilThereWillBeFreshData =
            getInfoResponse()!!.priceData.staleTimestampSecs - nowInSeconds

        val freshDataExists = secondsUntilThereWillBeFreshData < 0
        freshDataExists
    } else {
        // If we don't have a previous infoResponse object then we should refresh the data
        true
    }

    /**
     * Returns the cached InfoResponse if available.
     */
    fun getInfoResponse(): InfoResponse? = (_infoResponse.value as? InfoResponseState.Data)?.data

    sealed class InfoResponseState {
        data object Loading : InfoResponseState()
        data class Data(val data: InfoResponse) : InfoResponseState()
        data class Failure(val exception: Exception) : InfoResponseState()
    }

}
