// SnodeClock.kt
package org.session.libsession.network

import android.os.SystemClock
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class that manages the network time by querying the network time from a random snode.
 * The primary goal of this class is to provide a time that is not tied to current system time
 * and not prone to time changes locally.
 */
@Singleton
class SnodeClock @Inject constructor(
    @param:ManagerScope private val scope: CoroutineScope,
    private val snodeDirectory: SnodeDirectory,
    private val snodeClient: Lazy<SnodeClient>,
) {

    private val instantState = MutableStateFlow<Instant?>(null)

    // Concurrency & Throttling controls
    private val syncMutex = Mutex()
    private var activeSyncJob: Deferred<Boolean>? = null

    // Explicitly tracking "uptime" to handle device sleep/clock changes correctly
    private var lastSuccessfulSyncUptimeMs: Long = 0L

    // 10 Minutes in milliseconds
    private val minSyncIntervalMs = 10 * 60 * 1000L

    /**
     * Resync by querying 3 random snodes and setting time to the median of their adjusted times.
     * * Rules:
     * 1. If a sync is already running, this call waits for it and returns that result (coalescing).
     * 2. If a sync happened < 10 mins ago, returns false immediately.
     * 3. Returns true only if a fresh sync succeeded.
     */
    suspend fun resyncClock(): Boolean = coroutineScope {
        val jobToAwait = syncMutex.withLock {

            // 1. If a job is already running, join it.
            if (activeSyncJob?.isActive == true) {
                return@withLock activeSyncJob
            }

            // 2. Check throttling
            val now = SystemClock.elapsedRealtime()
            val timeSinceLastSync = now - lastSuccessfulSyncUptimeMs

            if (timeSinceLastSync < minSyncIntervalMs) {
                Log.d("SnodeClock", "Resync throttled (last sync ${timeSinceLastSync / 1000}s ago)")
                return@withLock null
            }

            // 3. Start a new job on the ManagerScope
            val newJob = scope.async {
                performNetworkSync()
            }
            activeSyncJob = newJob

            // 4. Cleanup when done
            newJob.invokeOnCompletion {
                scope.launch {
                    syncMutex.withLock {
                        // Only null it out if it hasn't been replaced by a newer job (rare but possible)
                        if (activeSyncJob === newJob) {
                            activeSyncJob = null
                        }
                    }
                }
            }

            newJob
        }

        // If jobToAwait is null, we were throttled.
        return@coroutineScope jobToAwait?.await() ?: false
    }

    /**
     * The actual logic to query Snodes.
     * This is private and only called by the controlled job inside resyncClock.
     */
    private suspend fun performNetworkSync(): Boolean {
        return runCatching {
            withTimeout(8_000L) {
                val nodes = pickDistinctRandomSnodes(count = 3)

                val samples: List<Pair<Long, Long>> = supervisorScope {
                    nodes.map { node ->
                        async {
                            runCatching {
                                val requestStarted = SystemClock.elapsedRealtime()
                                var networkTime = snodeClient.get().getNetworkTime(node).second
                                val requestEnded = SystemClock.elapsedRealtime()

                                // Adjust for latency
                                networkTime -= (requestEnded - requestStarted) / 2
                                requestStarted to networkTime
                            }.getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                }

                // Check for empty samples to prevent IndexOutOfBoundsException
                if (samples.isEmpty()) {
                    Log.w("SnodeClock", "Resync failed: Unable to reach any Snodes.")
                    return@withTimeout false
                }

                val nowUptime = SystemClock.elapsedRealtime()
                val candidateNowTimes = samples.map { (uptimeAtStart, adjustedAtStart) ->
                    adjustedAtStart + (nowUptime - uptimeAtStart)
                }.sorted()

                // Calculate median
                val medianNow = candidateNowTimes[candidateNowTimes.size / 2]

                // Commit state
                instantState.value = Instant(systemUptime = nowUptime, networkTime = medianNow)

                // Update throttling timer on SUCCESS only, protected by Mutex
                syncMutex.withLock {
                    lastSuccessfulSyncUptimeMs = SystemClock.elapsedRealtime()
                }

                Log.d("SnodeClock", "Resynced. Network time: ${Date(medianNow)}, system time: ${Date()}")
                true
            }
        }.getOrElse { t ->
            Log.w("SnodeClock", "Resync failed with exception", t)
            false
        }
    }

    private suspend fun pickDistinctRandomSnodes(count: Int): List<org.session.libsignal.utilities.Snode> {
        val out = LinkedHashSet<org.session.libsignal.utilities.Snode>(count)
        var guard = 0
        // Added a sanity check for pool size to prevent infinite loops if pool is tiny
        val poolSize = snodeDirectory.getSnodePool().size

        while (out.size < count && out.size < poolSize && guard++ < 20) {
            out += snodeDirectory.getRandomSnode()
        }
        return out.toList()
    }

    /**
     * Get the current time in milliseconds. If the network time is not available yet, this method
     * will return the current system time.
     */
    fun currentTimeMillis(): Long {
        return instantState.value?.now() ?: System.currentTimeMillis()
    }

    fun currentTimeSeconds(): Long = currentTimeMillis() / 1000

    fun currentTime(): java.time.Instant = java.time.Instant.ofEpochMilli(currentTimeMillis())

    /**
     * Delay until the specified instant. If the instant is in the past or now, this method returns
     * immediately.
     *
     * @return true if delayed, false if the instant is in the past
     */
    suspend fun delayUntil(instant: java.time.Instant): Boolean {
        val now = currentTimeMillis()
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