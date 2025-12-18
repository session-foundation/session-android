package org.session.libsession.network

import android.os.SystemClock
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class that manages the network time by querying the network time from a random snode. The
 * primary goal of this class is to provide a time that is not tied to current system time and not
 * prone to time changes locally.
 *
 * Before the first network query is successfully, calling [currentTimeMills] will return the current
 * system time.
 */
@Singleton
class SnodeClock @Inject constructor(
    @param:ManagerScope private val scope: CoroutineScope,
    private val snodeDirectory: SnodeDirectory,
    private val sessionClient: Lazy<SessionClient>,
) : OnAppStartupComponent {

    //todo ONION we have a lot of calls to MessagingModuleConfiguration.shared.snodeClock.currentTimeMills()
    // can this be improved?

    private val instantState = MutableStateFlow<Instant?>(null)
    private var job: Job? = null

    override fun onPostAppStarted() {
        require(job == null) { "Already started" }

        job = scope.launch {
            while (true) {
                try {
                    val node = snodeDirectory.getRandomSnode()
                    val requestStarted = SystemClock.elapsedRealtime()

                    var networkTime = sessionClient.get().getNetworkTime(node).second
                    val requestEnded = SystemClock.elapsedRealtime()

                    // Adjust network time to halfway through the request duration
                    networkTime -= (requestEnded - requestStarted) / 2

                    val inst = Instant(requestStarted, networkTime)

                    Log.d(
                        "SnodeClock",
                        "Network time: ${Date(inst.now())}, system time: ${Date()}"
                    )

                    instantState.value = inst
                } catch (e: Exception) {
                    Log.e("SnodeClock", "Failed to get network time. Retrying in a few seconds", e)
                } finally {
                    val delayMills = if (instantState.value == null) {
                        3_000L
                    } else {
                        3_600_000L
                    }

                    delay(delayMills)
                }
            }
        }
    }

    /**
     * Wait for the network adjusted time to come through.
     */
    suspend fun waitForNetworkAdjustedTime(): Long {
        return instantState.filterNotNull().first().now()
    }

    /**
     * Get the current time in milliseconds. If the network time is not available yet, this method
     * will return the current system time.
     */
    fun currentTimeMills(): Long {
        return instantState.value?.now() ?: System.currentTimeMillis()
    }

    fun currentTimeSeconds(): Long {
        return currentTimeMills() / 1000
    }

    fun currentTime(): java.time.Instant {
        return java.time.Instant.ofEpochMilli(currentTimeMills())
    }

    /**
     * Delay until the specified instant. If the instant is in the past or now, this method returns
     * immediately.
     *
     * @return true if delayed, false if the instant is in the past
     */
    suspend fun delayUntil(instant: java.time.Instant): Boolean {
        val now = currentTimeMills()
        val target = instant.toEpochMilli()
        return if (target > now) {
            delay(target - now)
            true
        } else {
            target == now
        }
    }

    private class Instant(
        val systemUptime: Long,
        val networkTime: Long,
    ) {
        fun now(): Long {
            val elapsed = SystemClock.elapsedRealtime() - systemUptime
            return networkTime + elapsed
        }
    }
}
