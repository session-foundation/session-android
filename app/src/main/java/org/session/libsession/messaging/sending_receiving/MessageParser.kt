package org.session.libsession.messaging.sending_receiving

import network.loki.messenger.libsession_util.SessionEncrypt
import network.loki.messenger.libsession_util.pro.ProProof
import network.loki.messenger.libsession_util.protocol.DecodedEnvelope
import network.loki.messenger.libsession_util.protocol.DecodedPro
import network.loki.messenger.libsession_util.protocol.SessionProtocol
import network.loki.messenger.libsession_util.util.asSequence
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
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.protos.SessionProtos
import org.thoughtcrime.securesms.preferences.CommunicationPreferenceKeys
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.pro.ProBackendConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MessageParser @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val snodeClock: SnodeClock,
    private val prefs: PreferenceStorage,
    private val proBackendConfig: Provider<ProBackendConfig>,
) {

    // A faster way to check if the user is blocked than to go through RecipientRepository
    private fun isUserBlocked(accountId: AccountId): Boolean {
        return configFactory.withUserConfigs { it.contacts.get(accountId.hexString) }
            ?.blocked == true
    }

    class ParseResult(
        val message: Message,
        val proto: SessionProtos.Content,
        val pro: DecodedPro?
    )


    private fun createMessageFromProto(proto: SessionProtos.Content, isGroupMessage: Boolean): Message {
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
        currentUserBlindedIDs: List<AccountId>,
        senderIdPrefix: IdPrefix
    ): ParseResult {
        return parseMessage(
            sender = AccountId(senderIdPrefix, decodedEnvelope.senderX25519PubKey.data),
            contentPlaintext = decodedEnvelope.contentPlainText.data,
            pro = decodedEnvelope.decodedPro,
            messageTimestampMs = decodedEnvelope.timestamp.toEpochMilli(),
            relaxSignatureCheck = relaxSignatureCheck,
            checkForBlockStatus = checkForBlockStatus,
            isForGroup = isForGroup,
            currentUserId = currentUserId,
            currentUserBlindedIDs = currentUserBlindedIDs,
        )
    }

    private fun parseMessage(
        sender: AccountId,
        contentPlaintext: ByteArray,
        pro: DecodedPro?,
        messageTimestampMs: Long,
        relaxSignatureCheck: Boolean,
        checkForBlockStatus: Boolean,
        isForGroup: Boolean,
        currentUserId: AccountId,
        currentUserBlindedIDs: List<AccountId>,
    ): ParseResult {
        val proto = SessionProtos.Content.parseFrom(contentPlaintext)

        // Check signature
        if (proto.hasSigTimestamp()) {
            val diff = abs(proto.sigTimestamp - messageTimestampMs)
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
        val isSenderSelf = sender == currentUserId || sender in currentUserBlindedIDs
        if (isSenderSelf && !message.isSelfSendValid) {
            throw NonRetryableException("Ignoring self send message")
        }

        // Fill in message fields
        message.sender = sender.hexString
        message.recipient = currentUserId.hexString
        message.sentTimestamp = messageTimestampMs
        message.receivedTimestamp = snodeClock.currentTimeMillis()
        message.isSenderSelf = isSenderSelf

        // Only process pro features post pro launch
        if (prefs[CommunicationPreferenceKeys.FORCE_POST_PRO]) {
            if (pro?.status == ProProof.STATUS_VALID) {
                (message as? VisibleMessage)?.proFeatures = buildSet {
                    addAll(pro.proMessageFeatures.asSequence())
                    addAll(pro.proProfileFeatures.asSequence())
                }
            }
        }

        // Validate
        var isValid = message.isValid()
        // TODO: Legacy code: why this is check needed?
        if (message is VisibleMessage && !isValid && proto.dataMessage.attachmentsCount != 0) { isValid = true }
        if (!isValid) {
            throw NonRetryableException("Invalid message")
        }

        // Duplicate check
        // TODO: Legacy code: this is most likely because we try to duplicate the message we just
        // send (so that a new polling won't get the same message). At the moment it's the only reliable
        // way to de-duplicate sent messages as we can add the "timestamp" before hand so that when
        // message arrives back from server we can identify it. The logic can be removed if we can
        // calculate message hash before sending it out so we can use the existing hash de-duplication
        // mechanism.
        if (storage.isDuplicateMessage(messageTimestampMs)) {
            throw NonRetryableException("Duplicate message")
        }
        storage.addReceivedMessageTimestamp(messageTimestampMs)

        return ParseResult(
            message = message,
            proto = proto,
            pro = pro
        )
    }


    fun parse1o1Message(
        data: ByteArray,
        serverHash: String?,
        currentUserEd25519PrivKey: ByteArray,
        currentUserId: AccountId,
    ): ParseResult {
        val envelop = SessionProtocol.decodeFor1o1(
            myEd25519PrivKey = currentUserEd25519PrivKey,
            payload = data,
            proBackendPubKey = proBackendConfig.get().ed25519PubKey,
        )

        return parseMessage(
            decodedEnvelope = envelop,
            relaxSignatureCheck = false,
            checkForBlockStatus = true,
            isForGroup = false,
            senderIdPrefix = IdPrefix.STANDARD,
            currentUserId = currentUserId,
            currentUserBlindedIDs = emptyList(),
        ).also { result ->
            result.message.serverHash = serverHash
        }
    }

    fun parseGroupMessage(
        data: ByteArray,
        serverHash: String,
        groupId: AccountId,
        currentUserEd25519PrivKey: ByteArray,
        currentUserId: AccountId,
    ): ParseResult {
        val keys = configFactory.withGroupConfigs(groupId) {
            it.groupKeys.groupKeys()
        }

        val decoded = SessionProtocol.decodeForGroup(
            payload = data,
            myEd25519PrivKey = currentUserEd25519PrivKey,
            groupEd25519PublicKey = groupId.pubKeyBytes,
            groupEd25519PrivateKeys = keys.toTypedArray(),
            proBackendPubKey = proBackendConfig.get().ed25519PubKey,
        )

        return parseMessage(
            decodedEnvelope = decoded,
            relaxSignatureCheck = false,
            checkForBlockStatus = false,
            isForGroup = true,
            senderIdPrefix = IdPrefix.STANDARD,
            currentUserId = currentUserId,
            currentUserBlindedIDs = emptyList(),
        ).also { result ->
            result.message.serverHash = serverHash
        }
    }

    fun parseCommunityMessage(
        msg: OpenGroupApi.Message,
        currentUserId: AccountId,
        currentUserBlindedIDs: List<AccountId>,
    ): ParseResult? {
        if (msg.data.isNullOrBlank()) {
            return null
        }

        val decoded = SessionProtocol.decodeForCommunity(
            payload = Base64.decode(msg.data),
            timestampMs = msg.posted?.toEpochMilli() ?: 0L,
            proBackendPubKey = proBackendConfig.get().ed25519PubKey,
        )

        val sender = AccountId(msg.sessionId)

        return parseMessage(
            contentPlaintext = decoded.contentPlainText.data,
            pro = decoded.decodedPro,
            relaxSignatureCheck = true,
            checkForBlockStatus = false,
            isForGroup = false,
            currentUserId = currentUserId,
            sender = sender,
            messageTimestampMs = msg.posted?.toEpochMilli() ?: 0L,
            currentUserBlindedIDs = currentUserBlindedIDs,
        ).also { result ->
            result.message.openGroupServerMessageID = msg.id
        }
    }

    fun parseCommunityDirectMessage(
        msg: OpenGroupApi.DirectMessage,
        communityServerPubKeyHex: String,
        currentUserEd25519PrivKey: ByteArray,
        currentUserId: AccountId,
        currentUserBlindedIDs: List<AccountId>,
    ): ParseResult {
        val (senderId, plaintext) = SessionEncrypt.decryptForBlindedRecipient(
            ciphertext = Base64.decode(msg.message),
            myEd25519Privkey = currentUserEd25519PrivKey,
            openGroupPubkey = Hex.fromStringCondensed(communityServerPubKeyHex),
            senderBlindedId = Hex.fromStringCondensed(msg.sender),
            recipientBlindId = Hex.fromStringCondensed(msg.recipient),
        )

        val decoded = SessionProtocol.decodeForCommunity(
            payload = plaintext.data,
            timestampMs = msg.postedAt?.toEpochMilli() ?: 0L,
            proBackendPubKey = proBackendConfig.get().ed25519PubKey,
        )

        val sender = Address.Standard(AccountId(senderId))

        return parseMessage(
            contentPlaintext = decoded.contentPlainText.data,
            pro = decoded.decodedPro,
            relaxSignatureCheck = true,
            checkForBlockStatus = false,
            isForGroup = false,
            currentUserId = currentUserId,
            sender = sender.accountId,
            messageTimestampMs = msg.postedAt?.toEpochMilli() ?: 0L,
            currentUserBlindedIDs = currentUserBlindedIDs,
        )
    }
}
