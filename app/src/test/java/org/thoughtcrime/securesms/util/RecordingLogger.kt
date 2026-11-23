package org.thoughtcrime.securesms.util

import org.session.libsignal.utilities.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [Log.Logger] that keeps every entry, for tests that need to assert what was logged.
 */
class RecordingLogger : Log.Logger() {
    data class Entry(val level: String, val tag: String?, val message: String?, val throwable: Throwable?)

    val entries: MutableList<Entry> = CopyOnWriteArrayList()

    private fun record(level: String, tag: String?, message: String?, t: Throwable?) {
        entries += Entry(level, tag, message, t)
    }

    override fun v(tag: String?, message: String?, t: Throwable?) = record("V", tag, message, t)
    override fun d(tag: String?, message: String?, t: Throwable?) = record("D", tag, message, t)
    override fun i(tag: String?, message: String?, t: Throwable?) = record("I", tag, message, t)
    override fun w(tag: String?, message: String?, t: Throwable?) = record("W", tag, message, t)
    override fun e(tag: String?, message: String?, t: Throwable?) = record("E", tag, message, t)
    override fun wtf(tag: String?, message: String?, t: Throwable?) = record("WTF", tag, message, t)
    override fun blockUntilAllWritesFinished() = Unit
}
