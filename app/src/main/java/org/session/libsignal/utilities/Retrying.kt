package org.session.libsignal.utilities

import kotlinx.coroutines.delay
import org.session.libsignal.exceptions.NonRetryableException
import kotlin.coroutines.cancellation.CancellationException

suspend fun <T> retryWithUniformInterval(maxRetryCount: Int = 3, retryIntervalMills: Long = 1000L, body: suspend () -> T): T {
    var retryCount = 0
    while (true) {
        try {
            return body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NonRetryableException) {
            throw e
        } catch (e: Exception) {
            Log.w("", "Exception while performing retryWithUniformInterval:", e)
            if (retryCount == maxRetryCount) {
                throw e
            } else {
                retryCount += 1
                delay(retryIntervalMills)
            }
        }
    }
}
