package org.thoughtcrime.securesms.webrtc.locks

import android.os.PowerManager
import org.session.libsignal.utilities.Log

/**
 * Controls access to the proximity lock.
 * The proximity lock is not part of the public API.
 */
class ProximityLock(pm: PowerManager) {

    private val proximityLock: PowerManager.WakeLock? =
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "signal:proximity"
            )
        } else {
            null
        }

    fun acquire() {
        val lock = proximityLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    fun release() {
        val lock = proximityLock ?: return
        if (!lock.isHeld) return

        lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        Log.d(TAG, "Released proximity lock:${lock.isHeld}")
    }

    private companion object {
        private val TAG = ProximityLock::class.java.simpleName
    }
}
