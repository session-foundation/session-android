package org.thoughtcrime.securesms.util

import android.os.SystemClock
import android.view.View

// Listener class that only accepts clicks at a given interval to prevent button spam.
// Note: While this cannot be used on conversation views without interfering with motion events it may still be useful.
class SafeClickListener(
    private var minimumClickIntervalMS: Long = 500L,
    private val onSafeClick: (View) -> Unit
) : View.OnClickListener {
    private var lastClickTimestampMS: Long = 0L

    override fun onClick(v: View) {
        // Ignore any follow-up clicks if the minimum interval has not passed
        if (SystemClock.elapsedRealtime() - lastClickTimestampMS < minimumClickIntervalMS) return

        lastClickTimestampMS = SystemClock.elapsedRealtime()
        onSafeClick(v)
    }
}