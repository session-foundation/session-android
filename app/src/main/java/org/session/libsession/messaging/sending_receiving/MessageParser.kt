package org.session.libsession.messaging.sending_receiving

import network.loki.messenger.libsession_util.protocol.DecodedEnvelope
import network.loki.messenger.libsession_util.protocol.SessionProtocol
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.messages.control.TypingIndicator
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.ParsedMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.IdPrefix
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MessageParser @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val snodeClock: SnodeClock,
) {

    //TODO: Obtain proBackendKey from somewhere
    private val proBackendKey = ByteArray(32)

    // A faster way to check if the user is blocked than to go through RecipientRepository
    private fun isUserBlocked(accountId: AccountId): Boolean {
        return configFactory.withUserConfigs { it.contacts.get(accountId.hexString) }
            ?.blocked == true
    }


    private fun createMessageFromProto(proto: SignalServiceProtos.Content, isGroupMessage: Boolean): Message {
        val message = ReadReceipt.fromProto(proto) ?:
        TypingIndicator.fromProto(proto) ?:
        DataExtractionNotification.fromProto(proto) ?:
        ExpirationTimerUpdate.fromProto(proto, isGroupMessage) ?:
        UnsendRequest.fromProto(proto) ?:
        MessageRequestResponse.fromProto(proto) ?:
        CallMessage.fromProto(proto) ?:
        GroupUpdated.fromProto(proto) ?:
        VisibleMessage.fromProto(proto)

        if (message == null) {
            throw NonRetryableException("Unknown message type")
        }

        return message
    }

    private fun parseMessage(
        decodedEnvelope: DecodedEnvelope,
        relaxSignatureCheck: Boolean,
        checkForBlockStatus: Boolean,
        isForGroup: Boolean,
        currentUserId: AccountId,
        alternativeCurrentUserIds: List<AccountId>,
        senderIdPrefix: IdPrefix
    ): Pair<Message, SignalServiceProtos.Content> {
        return parseMessage(
            sender = AccountId(senderIdPrefix, decodedEnvelope.senderX25519PubKey.data),
            contentPlaintext = decodedEnvelope.contentPlainText.data,
            messageTimestampMs = decodedEnvelope.timestamp.toEpochMilli(),
            relaxSignatureCheck = relaxSignatureCheck,
            checkForBlockStatus = checkForBlockStatus,
            isForGroup = isForGroup,
            currentUserId = currentUserId,
            alternativeCurrentUserIds = alternativeCurrentUserIds,
        )
    }

    private fun parseMessage(
        sender: AccountId,
        contentPlaintext: ByteArray,
        messageTimestampMs: Long,
        relaxSignatureCheck: Boolean,
        checkForBlockStatus: Boolean,
        isForGroup: Boolean,
        currentUserId: AccountId,
        alternativeCurrentUserIds: List<AccountId>,
    ): Pair<Message, SignalServiceProtos.Content> {
        val proto = SignalServiceProtos.Content.parseFrom(contentPlaintext)

        // Check signature
        if (proto.hasSigTimestampMs()) {
            val diff = abs(proto.sigTimestampMs - messageTimestampMs)
            if (
                (!relaxSignatureCheck && diff != 0L ) ||
                (relaxSignatureCheck && diff > TimeUnit.HOURS.toMillis(6))) {
                throw NonRetryableException("Invalid signature timestamp")
            }
        }

        val message = createMessageFromProto(proto, isGroupMessage = isForGroup)

        // Blocked sender check
        if (checkForBlockStatus && isUserBlocked(sender) && message.shouldDiscardIfBlocked()) {
            throw NonRetryableException("Sender($sender) is blocked from sending message to us")
        }

        // Valid self-send messages
        val isSenderSelf = sender == currentUserId || sender in alternativeCurrentUserIds
        if (isSenderSelf && !message.isSelfSendValid) {
            throw NonRetryableException("Ignoring self send message")
        }

        // Fill in message fields
        message.sender = sender.hexString
        message.recipient = currentUserId.hexString
        message.sentTimestamp = messageTimestampMs
        message.receivedTimestamp = snodeClock.currentTimeMills()
        message.isSenderSelf = isSenderSelf

        // Validate
        var isValid = message.isValid()
        // TODO: Legacy code: why this is check needed?
        if (message is VisibleMessage && !isValid && proto.dataMessage.attachmentsCount != 0) { isValid = true }
        if (!isValid) {
            throw NonRetryableException("Invalid message")
        }

        // Duplicate check
        // TODO: Legacy code: find out why is this needed again (it was done using server hash when receiving messages)
        if (storage.isDuplicateMessage(messageTimestampMs)) {
            throw NonRetryableException("Duplicate message")
        }
        storage.addReceivedMessageTimestamp(messageTimestampMs)

        return message to proto
    }


    fun parse1o1Message(
        data: ByteArray,
        serverHash: String,
    ): Pair<Message, SignalServiceProtos.Content> {
        val envelop = SessionProtocol.decodeFor1o1(
            myEd25519PrivKey = requireNotNull(storage.getUserED25519KeyPair()?.secretKey?.data) {
                "Couldn't find current user's key"
            },
            payload = data,
            nowEpochMs = snodeClock.currentTimeMills(),
            proBackendPubKey = proBackendKey,
        )

        return parseMessage(
            decodedEnvelope = envelop,
            relaxSignatureCheck = false,
            checkForBlockStatus = true,
            isForGroup = false,
            senderIdPrefix = IdPrefix.STANDARD,
            currentUserId = AccountId(storage.getUserPublicKey()!!),
            alternativeCurrentUserIds = emptyList(),
        ).also { (message, _) ->
            message.serverHash = serverHash
        }
    }

    fun parseGroupMessage(
        data: ByteArray,
        serverHash: String,
        groupId: AccountId,
    ): Pair<Message, SignalServiceProtos.Content> {
        val keys = configFactory.withGroupConfigs(groupId) {
            it.groupKeys.groupKeys()
        }

        val decoded = SessionProtocol.decodeForGroup(
            payload = data,
            myEd25519PrivKey = requireNotNull(storage.getUserED25519KeyPair()?.secretKey?.data) {
                "Couldn't find current user's key"
            },
            nowEpochMs = snodeClock.currentTimeMills(),
            groupEd25519PublicKey = groupId.pubKeyBytes,
            groupEd25519PrivateKeys = keys.toTypedArray(),
            proBackendPubKey = proBackendKey
        )

        return parseMessage(
            decodedEnvelope = decoded,
            relaxSignatureCheck = false,
            checkForBlockStatus = false,
            isForGroup = true,
            senderIdPrefix = IdPrefix.STANDARD,
            currentUserId = AccountId(storage.getUserPublicKey()!!),
            alternativeCurrentUserIds = emptyList(),
        ).also { (message, _) ->
            message.serverHash = serverHash
        }
    }

    fun parseCommunityMessage(
        msg: OpenGroupMessage,
        communityServerPubKeyHex: String,
    ): Pair<Message, SignalServiceProtos.Content> {
        val decoded = SessionProtocol.decodeForCommunity(
            payload = Base64.decode(msg.base64EncodedData.orEmpty()),
            nowEpochMs = snodeClock.currentTimeMills(),
            proBackendPubKey = proBackendKey,
        )

        val sender = AccountId(msg.sender!!)

        val keyPair = requireNotNull(storage.getUserED25519KeyPair()) {
            "Couldn't find current user's key"
        }

        val currentUserId = AccountId(IdPrefix.STANDARD, keyPair.pubKey.data)

        return parseMessage(
            contentPlaintext = decoded.contentPlainText.data,
            relaxSignatureCheck = true,
            checkForBlockStatus = false,
            isForGroup = false,
            currentUserId = currentUserId,
            sender = sender,
            messageTimestampMs = msg.sentTimestamp,
            alternativeCurrentUserIds = BlindKeyAPI.blind15Ids(
                sessionId = currentUserId.hexString,
                serverPubKey = communityServerPubKeyHex,
            ).map(::AccountId),
        ).also { (message, _) ->
            message.openGroupServerMessageID = msg.serverID
        }
    }

    fun parseCommunityInboxMessage(
        data: ByteArray,
        isOutgoing: Boolean,
        otherBlindedPublicKey: String,
        communityServerPubKeyHex: String,
    ): ParsedMessage {
        TODO()
    }
}