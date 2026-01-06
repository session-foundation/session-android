// SnodeClock.kt
package org.session.libsession.network

import android.os.SystemClock
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
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
    private val snodeClient: Lazy<SnodeClient>,
) : OnAppStartupComponent {

    //todo ONION we have a lot of calls to MessagingModuleConfiguration.shared.snodeClock.currentTimeMills()
    // can this be improved?

    private val instantState = MutableStateFlow<Instant?>(null)

    override fun onPostAppStarted() {
        scope.launch {
            resyncClock()
        }
    }

    /**
     * Resync by querying 3 random snodes and setting time to the median of their adjusted times.
     */
    suspend fun resyncClock(): Boolean {
        return runCatching {
            // Keep it bounded - clock sync shouldn't hang onion retries forever
            withTimeout(8_000L) {
                val nodes = pickDistinctRandomSnodes(count = 3)

                val samples: List<Pair<Long, Long>> = supervisorScope {
                    nodes.map { node ->
                        async {
                            val requestStarted = SystemClock.elapsedRealtime()
                            var networkTime = snodeClient.get().getNetworkTime(node).second
                            val requestEnded = SystemClock.elapsedRealtime()

                            // midpoint adjustment
                            networkTime -= (requestEnded - requestStarted) / 2

                            // (systemUptimeAtStart, adjustedNetworkTimeAtStart)
                            requestStarted to networkTime
                        }
                    }.awaitAll()
                }

                // Convert all samples to "time at (roughly) now" so they’re comparable,
                // then take the median.
                val nowUptime = SystemClock.elapsedRealtime()
                val candidateNowTimes = samples.map { (uptimeAtStart, adjustedAtStart) ->
                    val elapsed = nowUptime - uptimeAtStart
                    adjustedAtStart + elapsed
                }.sorted()

                val medianNow = candidateNowTimes[candidateNowTimes.size / 2]

                // Store as (systemUptimeNow, networkTimeNow)
                val inst = Instant(systemUptime = nowUptime, networkTime = medianNow)
                instantState.value = inst

                Log.d("SnodeClock", "Resynced. Network time: ${Date(inst.now())}, system time: ${Date()}")
                true
            }
        }.getOrElse { t ->
            Log.w("SnodeClock", "Resync failed", t)
            false
        }
    }

    private suspend fun pickDistinctRandomSnodes(count: Int): List<org.session.libsignal.utilities.Snode> {
        val out = LinkedHashSet<org.session.libsignal.utilities.Snode>(count)
        var guard = 0
        while (out.size < count && guard++ < 20) {
            out += snodeDirectory.getRandomSnode()
        }
        return out.toList()
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

    fun currentTimeSeconds(): Long = currentTimeMills() / 1000

    fun currentTime(): java.time.Instant = java.time.Instant.ofEpochMilli(currentTimeMills())

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
