package org.thoughtcrime.securesms.net

import androidx.annotation.WorkerThread
import org.session.libsession.utilities.Util
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import kotlin.concurrent.thread

class CallRequestController(
    private val call: Call
) : RequestController {

    private val done = CountDownLatch(1)
    private val canceled = AtomicBoolean(false)

    @Volatile
    private var stream: InputStream? = null

    override fun cancel() {
        // Don't block the caller; cancel on a background thread
        if (!canceled.compareAndSet(false, true)) return

        thread(name = "CallRequestController-cancel", isDaemon = true) {
            call.cancel()
            stream?.let(Util::close)
            stream = null
            done.countDown()
        }
    }

    fun setStream(stream: InputStream) {
        // If already canceled, immediately close what we were given.
        if (canceled.get()) {
            Util.close(stream)
            return
        }

        this.stream = stream
        done.countDown()
    }

    /**
     * Blocks until the stream is available or until the request is canceled.
     *
     * @return the stream, or null if canceled before it was set
     */
    @WorkerThread
    fun getStream(): InputStream? {
        done.await()
        return if (canceled.get()) null else stream
    }
}
