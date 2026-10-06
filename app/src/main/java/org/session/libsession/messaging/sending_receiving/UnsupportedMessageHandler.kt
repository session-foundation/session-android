package org.session.libsession.messaging.sending_receiving

import network.loki.messenger.BuildConfig
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.messages.UnsupportedMessage
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.MessageExpirationManagerProtocol
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.UnsupportedMessageDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.content.UnsupportedMessageContent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class UnsupportedMessageHandler @Inject constructor(
    private val storage: Storage,
    private val mmsDatabase: MmsDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val unsupportedMessageDatabase: UnsupportedMessageDatabase,
    private val bannerStore: UnsupportedMessageBannerStore,
    private val messageExpirationManager: Provider<MessageExpirationManagerProtocol>,
    private val snodeClock: SnodeClock,
) {
    /**
     * Retains [message] and, when it belongs in a conversation that already exists, inserts a
     * placeholder for it there.
     */
    fun handle(
        context: ReceivedMessageProcessor.MessageProcessingContext,
        threadAddress: Address.Conversable,
        message: UnsupportedMessage,
    ) {
        val serverHash = requireNotNull(message.serverHash) { "An UnsupportedMessage always has a hash" }

        if (unsupportedMessageDatabase.exists(serverHash)) return

        // Never create a conversation (or a message request) for something we can't show. The
        // record is still retained either way so a newer client can recover it.
        val threadId = if (message.placement == UnsupportedMessage.Placement.NONE) null else {
            context.threadIDs[threadAddress]
                ?: storage.getThreadId(threadAddress)?.also { context.threadIDs[threadAddress] = it }
        }

        val placeholderId = threadId?.let { insertPlaceholder(context, it, threadAddress, message) }
        val nowMs = snodeClock.currentTimeMillis()

        unsupportedMessageDatabase.insert(
            kind = message.kind,
            swarmPublicKey = message.swarmPublicKey,
            namespace = message.namespace,
            hash = serverHash,
            serverTimestampMs = message.serverTimestampMs,
            serverExpiryMs = message.serverExpiryMs,
            data = message.rawData,
            placeholderMessageId = placeholderId?.id,
            // A placeholder owns the expiry through the normal disappearing-messages path
            expiresAtMs = if (placeholderId != null) null else UnsupportedMessageDetection.retainedExpiryMs(
                afterSendStartedAtMs = message.sentTimestamp.takeIf { message.expiryMode is ExpiryMode.AfterSend },
                afterSendDurationMs = message.expiryMode.expiryMillis.takeIf { message.expiryMode is ExpiryMode.AfterSend },
                serverTimestampMs = message.serverTimestampMs,
                serverExpiryMs = message.serverExpiryMs,
                defaultTtlMs = SnodeMessage.DEFAULT_TTL,
            ),
            receivedAtMs = nowMs,
            lastAttemptVersion = currentVersion(),
        )

        unsupportedMessageDatabase.enforceLimits(nowMs)

        Log.i(TAG, "Retained ${message.kind.dbValue} message $serverHash (${message.rawData.size} bytes), placeholder: ${placeholderId != null}")

        // Only things which say this device is behind, and which a stranger can't use to make a
        // claim about who sent what, raise the banner. An unknown type from someone else either has
        // a placeholder or came from a stranger.
        when {
            message.kind == UnsupportedMessage.Kind.NEWER_FORMAT ->
                bannerStore.recordTrigger(UnsupportedMessageBanner.Trigger.NEWER_FORMAT, nowMs)

            message.placement == UnsupportedMessage.Placement.NONE && message.isSenderSelf ->
                bannerStore.recordTrigger(UnsupportedMessageBanner.Trigger.OTHER_DEVICE, nowMs)
        }
    }

    private fun insertPlaceholder(
        context: ReceivedMessageProcessor.MessageProcessingContext,
        threadId: Long,
        threadAddress: Address.Conversable,
        message: UnsupportedMessage,
    ): MessageId? {
        val sentTimestamp = message.sentTimestamp!!
        val expiresInMillis = message.expiryMode.expiryMillis
        val expireStartedAt = if (message.expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0
        val group = threadAddress as? Address.GroupLike

        val messageId = if (message.placement == UnsupportedMessage.Placement.OUTGOING) {
            if (sentTimestamp > context.maxOutgoingMessageTimestamp) {
                context.maxOutgoingMessageTimestamp = sentTimestamp
            }

            mmsDatabase.insertSecureDecryptedMessageOutbox(
                retrieved = OutgoingMediaMessage(
                    recipient = threadAddress,
                    body = null,
                    attachments = emptyList(),
                    sentTimeMillis = sentTimestamp,
                    expiresInMillis = expiresInMillis,
                    expireStartedAtMillis = expireStartedAt,
                    outgoingQuote = null,
                    messageContent = UnsupportedMessageContent,
                    linkPreviews = emptyList(),
                    group = group,
                    isGroupUpdateMessage = false,
                ),
                threadId = threadId,
                serverTimestamp = sentTimestamp,
            )
        } else {
            mmsDatabase.insertSecureDecryptedMessageInbox(
                retrieved = IncomingMediaMessage(
                    from = message.sender!!.toAddress(),
                    sentTimeMillis = sentTimestamp,
                    expiresIn = expiresInMillis,
                    expireStartedAt = expireStartedAt,
                    isMessageRequestResponse = false,
                    hasMention = false,
                    body = null,
                    group = group,
                    attachments = emptyList(),
                    proFeatures = emptySet(),
                    messageContent = UnsupportedMessageContent,
                    quote = null,
                    linkPreviews = emptyList(),
                    dataExtractionNotification = null,
                ),
                threadId = threadId,
                serverTimestamp = message.receivedTimestamp ?: 0,
            )
        }?.let { MessageId(it.messageId, mms = true) } ?: return null

        lokiMessageDatabase.setMessageServerHash(messageId, message.serverHash!!)

        message.threadID = threadId
        message.id = messageId
        messageExpirationManager.get().onMessageReceived(message)

        return messageId
    }

    companion object {
        private const val TAG = "UnsupportedMessageHandler"

        fun currentVersion(): String = "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"
    }
}
