package org.thoughtcrime.securesms.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.TYPE_INCOMING_CONNECTING
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.WEBRTC_NOTIFICATION

class CallForegroundService : Service() {

    companion object {
        const val EXTRA_RECIPIENT_ADDRESS = "RECIPIENT_ID"
        const val EXTRA_TYPE = "CALL_STEP_TYPE"

        fun startIntent(context: Context, type: Int, recipient: Recipient?): Intent {
            return Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_RECIPIENT_ADDRESS, recipient?.address)
        }
    }

    private fun getRemoteRecipient(intent: Intent): Recipient? {
        val remoteAddress = IntentCompat.getParcelableExtra(intent,
            EXTRA_RECIPIENT_ADDRESS, Address::class.java)
            ?: return null

        return Recipient.from(this, remoteAddress, true)
    }

    private fun startForeground(type: Int, recipient: Recipient?) {
        if (CallNotificationBuilder.areNotificationsEnabled(this)) {
            try {
                ServiceCompat.startForeground(
                    this,
                    WEBRTC_NOTIFICATION,
                    CallNotificationBuilder.getCallInProgressNotification(this, type, recipient),
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
                )
                return
            } catch (e: IllegalStateException) {
                Log.e("", "Failed to setCallInProgressNotification as a foreground service for type: ${type}", e)
            }
        }

        // if we failed to start in foreground, stop service
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d("", "CallForegroundService onStartCommand: ${intent}")

        // check if the intent has the appropriate data to start this service, otherwise stop
        if(intent?.hasExtra(EXTRA_TYPE) == true){
          startForeground(intent.getIntExtra(EXTRA_TYPE, TYPE_INCOMING_CONNECTING), getRemoteRecipient(intent))
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}