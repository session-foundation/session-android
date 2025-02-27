package org.thoughtcrime.securesms.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getString
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.utils.Key
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import network.loki.messenger.R
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.sending_receiving.notifications.PushNotificationMetadata
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.messaging.utilities.SodiumUtilities.sodium
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.bencode.Bencode
import org.session.libsession.utilities.bencode.BencodeList
import org.session.libsession.utilities.bencode.BencodeString
import org.session.libsession.utilities.getGroup
import org.session.libsignal.protos.SignalServiceProtos.Envelope
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.GroupRevokedMessageHandler
import javax.inject.Inject

private const val TAG = "PushHandler"

class PushReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configFactory: ConfigFactory,
    private val groupRevokedMessageHandler: GroupRevokedMessageHandler,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Both push services should hit this method once they receive notification data
     * As long as it is properly formatted
     */
    fun onPushDataReceived(dataMap: Map<String, String>?) {
        addMessageReceiveJob(dataMap?.asPushData())
    }

    /**
     * This is a fallback method in case the Huawei data is malformated,
     * but it shouldn't happen. Old code used to send different data so this is kept as a safety
     */
    fun onPushDataReceived(data: ByteArray?) {
        addMessageReceiveJob(PushData(data = data, metadata = null))
    }

    private fun addMessageReceiveJob(pushData: PushData?) {
        try {
            val namespace = pushData?.metadata?.namespace
            val params = when {
                namespace == Namespace.GROUP_MESSAGES() ||
                        namespace == Namespace.REVOKED_GROUP_MESSAGES() ||
                        namespace == Namespace.GROUP_INFO() ||
                        namespace == Namespace.GROUP_MEMBERS() ||
                        namespace == Namespace.GROUP_KEYS() -> {
                    val groupId = AccountId(requireNotNull(pushData.metadata.account) {
                        "Received a closed group message push notification without an account ID"
                    })

                    if (configFactory.getGroup(groupId)?.shouldPoll != true) {
                        Log.d(TAG, "Received a push notification for a group that isn't active")
                        return
                    }

                    // send a generic notification if we have no data
                    if (pushData.data == null) {
                        sendGenericNotification()
                        return
                    }

                    if (namespace == Namespace.GROUP_MESSAGES()) {
                        val envelope = checkNotNull(tryDecryptGroupEnvelope(groupId, pushData.data)) {
                            "Unable to decrypt closed group message"
                        }

                        MessageReceiveParameters(
                            data = envelope.toByteArray(),
                            serverHash = pushData.metadata.msg_hash,
                            closedGroup = Destination.ClosedGroup(groupId.hexString)
                        )
                    } else if (namespace == Namespace.REVOKED_GROUP_MESSAGES()) {
                        GlobalScope.launch {
                            groupRevokedMessageHandler.handleRevokeMessage(groupId, listOf(pushData.data))
                        }

                        null
                    } else  {
                        val hash = requireNotNull(pushData.metadata.msg_hash) {
                            "Received a closed group config push notification without a message hash"
                        }

                        // If we receive group config messages from notification, try to merge
                        // them directly
                        val configMessage = listOf(
                            ConfigMessage(
                                hash = hash,
                                data = pushData.data,
                                timestamp = pushData.metadata.timestampSeconds
                            )
                        )

                        configFactory.mergeGroupConfigMessages(
                            groupId = groupId,
                            keys = configMessage.takeIf { namespace == Namespace.GROUP_KEYS() }
                                .orEmpty(),
                            members = configMessage.takeIf { namespace == Namespace.GROUP_MEMBERS() }
                                .orEmpty(),
                            info = configMessage.takeIf { namespace == Namespace.GROUP_INFO() }
                                .orEmpty(),
                        )

                        null
                    }
                }

                namespace == Namespace.DEFAULT() || pushData?.metadata == null -> {
                    // send a generic notification if we have no data
                    if (pushData?.data == null) {
                        sendGenericNotification()
                        return
                    }

                    val envelopeAsData = MessageWrapper.unwrap(pushData.data).toByteArray()
                    MessageReceiveParameters(
                        data = envelopeAsData,
                        serverHash = pushData.metadata?.msg_hash
                    )
                }

                else -> {
                    Log.w(TAG, "Received a push notification with an unknown namespace: $namespace")
                    return
                }
            }

            if (params != null) {
                JobQueue.shared.add(BatchMessageReceiveJob(listOf(params), null))
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to unwrap data for message due to error.", e)
        }

    }

    private fun tryDecryptGroupEnvelope(groupId: AccountId, data: ByteArray): Envelope? {
        val (envelopBytes, sender) = checkNotNull(configFactory.withGroupConfigs(groupId) {
            it.groupKeys.decrypt(
                data
            )
        }) {
            "Failed to decrypt group message"
        }

        Log.d(TAG, "Successfully decrypted group message from $sender")
        return Envelope.parseFrom(envelopBytes)
            .toBuilder()
            .setSource(sender.hexString)
            .build()
    }

    private fun sendGenericNotification() {
        Log.d(TAG, "Failed to decode data for message.")

        // no need to do anything if notification permissions are not granted
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(context, NotificationChannels.OTHER)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.textsecure_primary))
            .setContentTitle(getString(context, R.string.app_name))

            // Note: We set the count to 1 in the below plurals string so it says "You've got a new message" (singular)
            .setContentText(context.resources.getQuantityString(R.plurals.messageNewYouveGot, 1, 1))

            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        NotificationManagerCompat.from(context).notify(11111, builder.build())
    }

    private fun Map<String, String>.asPushData(): PushData =
        when {
            // this is a v2 push notification
            containsKey("spns") -> {
                try {
                    decrypt(Base64.decode(this["enc_payload"]))
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid push notification", e)
                    PushData(null, null)
                }
            }
            // old v1 push notification; we still need this for receiving legacy closed group notifications
            else -> PushData(this["ENCRYPTED_DATA"]?.let(Base64::decode), null)
        }

    private fun decrypt(encPayload: ByteArray): PushData {
        Log.d(TAG, "decrypt() called")

        val encKey = getOrCreateNotificationKey()
        val nonce = encPayload.sliceArray(0 until AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val payload =
            encPayload.sliceArray(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES until encPayload.size)
        val padded = SodiumUtilities.decrypt(payload, encKey.asBytes, nonce)
            ?: error("Failed to decrypt push notification")
        val contentEndedAt = padded.indexOfLast { it.toInt() != 0 }
        val decrypted = if (contentEndedAt >= 0) padded.sliceArray(0..contentEndedAt) else padded
        val bencoded = Bencode.Decoder(decrypted)
        val expectedList = (bencoded.decode() as? BencodeList)?.values
            ?: error("Failed to decode bencoded list from payload")

        val metadataJson = (expectedList[0] as? BencodeString)?.value ?: error("no metadata")
        val metadata: PushNotificationMetadata = json.decodeFromString(String(metadataJson))

        return PushData(
            data = (expectedList.getOrNull(1) as? BencodeString)?.value,
            metadata = metadata
        ).also { pushData ->
            // null data content is valid only if we got a "data_too_long" flag
            pushData.data?.let { check(metadata.data_len == it.size) { "wrong message data size" } }
                ?: check(metadata.data_too_long) { "missing message data, but no too-long flag" }
        }
    }

    fun getOrCreateNotificationKey(): Key {
        val keyHex = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.NOTIFICATION_KEY)
        if (keyHex != null) {
            return Key.fromHexString(keyHex)
        }

        // generate the key and store it
        val key = sodium.keygen(AEAD.Method.XCHACHA20_POLY1305_IETF)
        IdentityKeyUtil.save(context, IdentityKeyUtil.NOTIFICATION_KEY, key.asHexString)
        return key
    }

    data class PushData(
        val data: ByteArray?,
        val metadata: PushNotificationMetadata?
    )
}
