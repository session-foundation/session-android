package org.thoughtcrime.securesms.net

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.concurrent.SignalExecutors
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class CallRequestController(private val call: Call) : RequestController {

    private val canceled = AtomicBoolean(false)
    private val result = CompletableDeferred<InputStream?>()

    @Volatile
    private var stream: InputStream? = null

    override fun cancel() {
        if (!canceled.compareAndSet(false, true)) return

        call.cancel()

        // Unblock waiters first
        result.complete(null)

        // cancel() is called on the main thread (e.g. when Glide clears a request), and closing
        // a response body there can write to the TLS socket and throw NetworkOnMainThreadException.
        stream?.let { SignalExecutors.UNBOUNDED.execute { Util.close(it) } }
        stream = null
    }

    fun setStream(stream: InputStream) {
        // If already canceled or already completed, close immediately
        if (canceled.get()) {
            Util.close(stream)
            return
        }

        this.stream = stream

        // If we lost the race to cancel(), close what we were handed
        if (!result.complete(stream)) {
            Util.close(stream)
            this.stream = null
        }
    }

    @WorkerThread
    fun getStream(): InputStream? = try {
        runBlocking { result.await() }
    } catch (_: CancellationException) {
        null
    }
}
