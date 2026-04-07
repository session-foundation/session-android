package org.thoughtcrime.securesms.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.notifications.NotificationChannelManager
import org.thoughtcrime.securesms.notifications.NotificationId
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.Companion.TYPE_INCOMING_CONNECTING
import javax.inject.Inject

@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var notificationChannelManager: NotificationChannelManager

    @Inject
    lateinit var notificationManager: NotificationManagerCompat

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val EXTRA_RECIPIENT_ADDRESS = "RECIPIENT_ID"
        const val EXTRA_TYPE = "CALL_STEP_TYPE"

        fun startIntent(context: Context, type: Int, recipient: Address?): Intent {
            return Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_RECIPIENT_ADDRESS, recipient)
        }
    }

    private fun getRemoteRecipient(intent: Intent): Recipient? {
        val remoteAddress = IntentCompat.getParcelableExtra(intent,
            EXTRA_RECIPIENT_ADDRESS, Address::class.java)
            ?: return null

        return recipientRepository.getRecipientSync(remoteAddress)
    }

    private fun buildNotification(type: Int, recipient: Recipient?) =
        CallNotificationBuilder.getCallInProgressNotification(
            this,
            type,
            recipient,
            notificationChannelManager
        )

    private fun startForeground(type: Int): Boolean {
        try {
            ServiceCompat.startForeground(
                this,
                NotificationId.WEBRTC_CALL,
                buildNotification(type, recipient = null),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
            return true
        } catch (e: IllegalStateException) {
            Log.e("", "Failed to start CallForegroundService for type: ${type}", e)
        }

        stopSelf()
        return false
    }

    private fun updateNotificationWithRecipient(intent: Intent, type: Int) {
        serviceScope.launch {
            val recipient = withContext(Dispatchers.IO) {
                getRemoteRecipient(intent)
            }

            if (ActivityCompat.checkSelfPermission(
                    this@CallForegroundService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }

            try {
                notificationManager.notify(
                    NotificationId.WEBRTC_CALL,
                    buildNotification(type, recipient)
                )
            } catch (e: SecurityException) {
                Log.w("", "Failed to update call notification", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d("", "CallForegroundService onStartCommand: ${intent}")

        // check if the intent has the appropriate data to start this service, otherwise stop
        if (intent?.hasExtra(EXTRA_TYPE) == true) {
            val type = intent.getIntExtra(EXTRA_TYPE, TYPE_INCOMING_CONNECTING)
            if (startForeground(type)) {
                updateNotificationWithRecipient(intent, type)
            }
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
