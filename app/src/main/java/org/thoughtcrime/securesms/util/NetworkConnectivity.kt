package org.thoughtcrime.securesms.util

import android.app.Application
import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a flow that emits `true` when the device has network connectivity. We won't be sure
 * if there's internet or not, it's by designed so that we don't get false negatives in censorship
 * countries.
 */
@Singleton
class NetworkConnectivity @Inject constructor(application: Application) {
    val networkAvailable = callbackFlow {
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)

        val callback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)

                Log.v("NetworkConnectivity", "Network become available")
                trySend(true)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.v("NetworkConnectivity", "Network become lost")
                trySend(false)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.stateIn(
        scope = GlobalScope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = haveValidNetworkConnection(application)
    )


    companion object {
        // Method to determine if we have a valid Internet connection or not
        private fun haveValidNetworkConnection(context: Context): Boolean {
            val cm = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            return cm.activeNetwork != null
        }
    }

}
