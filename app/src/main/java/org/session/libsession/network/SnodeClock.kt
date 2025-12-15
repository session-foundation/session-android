@Singleton
class SnodeClock @Inject constructor(
    @ManagerScope private val scope: CoroutineScope,
    private val snodeDirectory: SnodeDirectory,
    private val sessionNetwork: SessionNetwork
) : OnAppStartupComponent {

    private val instantState = MutableStateFlow<Instant?>(null)
    private var job: Job? = null

    override fun onPostAppStarted() {
        require(job == null) { "Already started" }

        job = scope.launch {
            while (true) {
                try {
                    val node = pickRandomSnode() ?: run {
                        Log.e("SnodeClock", "No snodes available in pool; cannot query network time.")
                        delay(3_000L)
                        continue
                    }

                    val requestStarted = SystemClock.elapsedRealtime()

                    val networkTime = fetchNetworkTime(node)

                    val requestEnded = SystemClock.elapsedRealtime()
                    var adjustedNetworkTime = networkTime - (requestEnded - requestStarted) / 2

                    val inst = Instant(requestStarted, adjustedNetworkTime)

                    Log.d("SnodeClock", "Network time: ${Date(inst.now())}, system time: ${Date()}")

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

    private fun pickRandomSnode(): Snode? {
        val pool = snodeDirectory.getSnodePool()
        if (pool.isEmpty()) return null
        return pool.random()
    }

    private suspend fun fetchNetworkTime(snode: Snode): Long {
        val result = sessionNetwork.sendToSnode(
            method     = Snode.Method.Info,
            parameters = emptyMap<String, Any>(),
            snode      = snode,
            version    = Version.V4
        )

        if (result.isFailure) {
            throw result.exceptionOrNull()
                ?: IllegalStateException("Unknown error getting network time")
        }

        val response = result.getOrThrow()
        val body = response.body ?: error("Empty body for Info RPC")

        @Suppress("UNCHECKED_CAST")
        val json = JsonUtil.fromJson(body, Map::class.java) as Map<*, *>
        val timestamp = json["timestamp"] as? Long
            ?: throw IllegalStateException("Missing timestamp in Info response")

        return timestamp
    }

    // rest of your SnodeClock unchanged...

    suspend fun waitForNetworkAdjustedTime(): Long =
        instantState.filterNotNull().first().now()

    fun currentTimeMills(): Long =
        instantState.value?.now() ?: System.currentTimeMillis()

    fun currentTimeSeconds(): Long =
        currentTimeMills() / 1000

    fun currentTime(): java.time.Instant =
        java.time.Instant.ofEpochMilli(currentTimeMills())

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
