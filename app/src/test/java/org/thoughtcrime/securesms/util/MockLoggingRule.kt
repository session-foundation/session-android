package org.thoughtcrime.securesms.util

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.session.libsignal.utilities.Log

/**
 * Ensure the logger is set up so that the Android logger is not used during tests.
 */
class MockLoggingRule : TestWatcher() {
    override fun starting(description: Description?) {
        Log.initialize(TestLogger())
    }
}

class TestLogger : Log.Logger() {
    private fun printLog(level: String, tag: String?, message: String?, t: Throwable?) {
        val throwableMessage = t?.let { " - ${it.message}" } ?: ""
        println("[$level] $tag: $message$throwableMessage")
    }

    override fun v(tag: String?, message: String?, t: Throwable?) {
        printLog("VERBOSE", tag, message, t)
    }

    override fun d(tag: String?, message: String?, t: Throwable?) {
        printLog("DEBUG", tag, message, t)
    }

    override fun i(tag: String?, message: String?, t: Throwable?) {
        printLog("INFO", tag, message, t)
    }

    override fun w(tag: String?, message: String?, t: Throwable?) {
        printLog("WARN", tag, message, t)
    }

    override fun e(tag: String?, message: String?, t: Throwable?) {
        printLog("ERROR", tag, message, t)
    }

    override fun wtf(tag: String?, message: String?, t: Throwable?) {
        printLog("ASSERT", tag, message, t)
    }

    override fun blockUntilAllWritesFinished() {
        // No-op for test logger
    }
}