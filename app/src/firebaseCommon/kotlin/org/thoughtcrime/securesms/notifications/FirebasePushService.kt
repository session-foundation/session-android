package org.thoughtcrime.securesms.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.DateUtils
import javax.inject.Inject

private const val TAG = "FirebasePushNotificationService"

@AndroidEntryPoint
class FirebasePushService : FirebaseMessagingService() {

    @Inject lateinit var pushReceiver: PushReceiver
    @Inject lateinit var handler: PushRegistrationHandler
    @Inject lateinit var tokenFetcher: TokenFetcher
    @Inject lateinit var dateUtils: DateUtils

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token")
        tokenFetcher.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "Received a firebase push notification: $message - Priority received: ${message.priority} (Priority expected: ${message.originalPriority}) - Sent time: ${DateUtils.getLocaleFormattedDate(message.sentTime, "HH:mm:ss.SSS")}")
        pushReceiver.onPushDataReceived(message.data)
    }
}
