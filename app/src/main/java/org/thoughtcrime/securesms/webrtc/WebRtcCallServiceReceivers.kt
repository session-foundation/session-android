package org.thoughtcrime.securesms.webrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.webrtc.locks.LockManager
import javax.inject.Inject


class PowerButtonReceiver(val onScreenOffChange: ()->Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_SCREEN_OFF == intent.action) {
            onScreenOffChange()
        }
    }
}

class ProximityLockRelease(private val lockManager: LockManager): Thread.UncaughtExceptionHandler {
    companion object {
        private val TAG = Log.tag(ProximityLockRelease::class.java)
    }
    override fun uncaughtException(t: Thread, e: Throwable) {
        Log.e(TAG,"Uncaught exception - releasing proximity lock", e)
        lockManager.updatePhoneState(LockManager.PhoneState.IDLE)
    }
}

class WiredHeadsetStateReceiver(val onWiredHeadsetChanged: (Boolean)->Unit): BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getIntExtra("state", -1)
        onWiredHeadsetChanged(state != 0)
    }
}


@AndroidEntryPoint
class EndCallReceiver(): BroadcastReceiver() {
    @Inject
    lateinit var webRtcCallBridge: WebRtcCallBridge

    override fun onReceive(context: Context, intent: Intent) {
        when(intent.action) {
            WebRtcCallBridge.ACTION_LOCAL_HANGUP -> {
                webRtcCallBridge.handleLocalHangup(null)
            }

            WebRtcCallBridge.ACTION_IGNORE_CALL -> {
                webRtcCallBridge.handleIgnoreCall()
            }

            else -> webRtcCallBridge.handleDenyCall()
        }
    }
}