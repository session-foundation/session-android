package org.thoughtcrime.securesms.net

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.session.libsession.utilities.Util
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

        // Close any stream we might already have
        stream?.let(Util::close)
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
