package org.session.libsession.messaging.sending_receiving

import androidx.annotation.VisibleForTesting
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.SessionEncrypt
import network.loki.messenger.libsession_util.pro.ProProof
import network.loki.messenger.libsession_util.protocol.DecodedEnvelope
import network.loki.messenger.libsession_util.protocol.DecodedPro
import network.loki.messenger.libsession_util.protocol.SessionProtocol
import network.loki.messenger.libsession_util.util.asSequence
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.UnsupportedMessage
import org.session.libsession.messaging.messages.copyExpiration
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
import org.thoughtcrime.securesms.pro.ProBackendConfig
import org.thoughtcrime.securesms.pro.db.ProDatabase
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
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val proDatabase: ProDatabase,
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

    /**
     * Where a swarm message came from, exactly as retrieved, so an [UnsupportedMessage] can be
     * retained in a form that can be replayed through this parser later.
     */
    class SwarmOrigin(
        val rawData: ByteArray,
        val swarmPublicKey: String,
        val namespace: Int,
        val serverHash: String,
        val serverTimestampMs: Long,
        val serverExpiryMs: Long?,
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
        senderIdPrefix: IdPrefix,
        swarmOrigin: SwarmOrigin?,
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
            swarmOrigin = swarmOrigin,
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
        swarmOrigin: SwarmOrigin? = null,
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

        // Only swarm messages are considered: communities and their inboxes are open to anyone, so a
        // placeholder there would invite spam more than it informs.
        if (swarmOrigin != null &&
            UnsupportedMessageDetection.isUnknownType(contentPlaintext) {
                hasValidKnownContent(
                    proto = proto,
                    isForGroup = isForGroup,
                    sender = sender,
                    currentUserId = currentUserId,
                    messageTimestampMs = messageTimestampMs,
                )
            }
        ) {
            return parseUnknownTypeMessage(
                proto = proto,
                sender = sender,
                messageTimestampMs = messageTimestampMs,
                checkForBlockStatus = checkForBlockStatus,
                isForGroup = isForGroup,
                currentUserId = currentUserId,
                swarmOrigin = swarmOrigin,
            )
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

        // A cryptographically valid proof is not enough to carry features: the revocation list overrides
        // the validity of proofs already in circulation, and libsession's decode cannot see it because
        // the list is cached locally rather than travelling with the message.
        //
        // Cleared here rather than at each consumer so the bitset itself is truthful. Everything
        // downstream reads it without knowing about revocation — the character limit, the features shown
        // in message info, and what is persisted alongside the message.
        //
        // Honours the entry's effective timestamp, like every other read of the list, so a revocation
        // dated in the future does not withdraw features early.
        val proofRevoked = pro?.proof?.revocationTagHex
            ?.let { proDatabase.isRevoked(it, snodeClock.currentTime()) } == true

        if (pro?.status == ProProof.STATUS_VALID && !proofRevoked) {
            (message as? VisibleMessage)?.proFeatures = buildSet {
                addAll(pro.proMessageFeatures.asSequence())
                addAll(pro.proProfileFeatures.asSequence())
            }
        }

        // Validate
        if (!isValidMessage(message, proto)) {
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


    private fun isValidMessage(message: Message, proto: SessionProtos.Content): Boolean {
        // TODO: Legacy code: why this is check needed?
        return message.isValid() ||
                (message is VisibleMessage && proto.dataMessage.attachmentsCount != 0)
    }

    @VisibleForTesting
    internal fun hasValidKnownContent(
        proto: SessionProtos.Content,
        isForGroup: Boolean,
        sender: AccountId,
        currentUserId: AccountId,
        messageTimestampMs: Long,
    ): Boolean {
        val message = runCatching { createMessageFromProto(proto, isGroupMessage = isForGroup) }
            .getOrNull()
            ?: return false

        message.sender = sender.hexString
        message.recipient = currentUserId.hexString
        message.sentTimestamp = messageTimestampMs
        message.receivedTimestamp = snodeClock.currentTimeMillis()

        return isValidMessage(message, proto)
    }

    @VisibleForTesting
    internal fun parseUnknownTypeMessage(
        proto: SessionProtos.Content,
        sender: AccountId,
        messageTimestampMs: Long,
        checkForBlockStatus: Boolean,
        isForGroup: Boolean,
        currentUserId: AccountId,
        swarmOrigin: SwarmOrigin,
    ): ParseResult {
        val isSenderSelf = sender == currentUserId

        if (checkForBlockStatus && !isSenderSelf && isUserBlocked(sender)) {
            throw NonRetryableException("Sender($sender) is blocked from sending message to us")
        }

        val (placement, syncTarget) = UnsupportedMessageDetection.placement(
            isGroup = isForGroup,
            isSenderSelf = isSenderSelf,
            syncTarget = proto.dataMessage.takeIf { proto.hasDataMessage() && it.hasSyncTarget() }?.syncTarget,
        )

        val message = UnsupportedMessage(
            kind = UnsupportedMessage.Kind.UNKNOWN_TYPE,
            placement = placement,
            rawData = swarmOrigin.rawData,
            swarmPublicKey = swarmOrigin.swarmPublicKey,
            namespace = swarmOrigin.namespace,
            serverTimestampMs = swarmOrigin.serverTimestampMs,
            serverExpiryMs = swarmOrigin.serverExpiryMs,
            syncTarget = syncTarget,
        ).copyExpiration(proto).apply {
            this.sender = sender.hexString
            recipient = currentUserId.hexString
            sentTimestamp = messageTimestampMs
            receivedTimestamp = snodeClock.currentTimeMillis()
            this.isSenderSelf = isSenderSelf
            serverHash = swarmOrigin.serverHash
        }

        if (!message.isValid()) {
            throw NonRetryableException("Invalid message")
        }

        return ParseResult(message = message, proto = proto, pro = null)
    }

    fun parse1o1Message(
        data: ByteArray,
        serverHash: String?,
        currentUserEd25519PrivKey: ByteArray,
        currentUserId: AccountId,
        serverTimestampMs: Long,
        serverExpiryMs: Long?,
    ): ParseResult {
        // A message in a newer format is recognised from its first byte without decrypting it. A
        // message that merely fails to decrypt deliberately gets nothing at all: it can't be
        // attributed to anyone, so it could be spam, corruption or an attacker, and anything we
        // showed for it would claim that someone really sent us something. Even a newer-format
        // message only gets an account-level banner, never a bubble, because the format's prefix
        // is unauthenticated and its sender is inside the encryption.
        if (UnsupportedMessageDetection.isNewerFormat(data)) {
            return parseNewerFormatMessage(
                data = data,
                serverHash = serverHash
                    // Only push without metadata lacks a hash, and the poller fetches the same
                    // message again with one.
                    ?: throw NonRetryableException("Newer format message without a hash"),
                currentUserId = currentUserId,
                serverTimestampMs = serverTimestampMs,
                serverExpiryMs = serverExpiryMs,
            )
        }

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
            swarmOrigin = serverHash?.let {
                SwarmOrigin(
                    rawData = data,
                    swarmPublicKey = currentUserId.hexString,
                    namespace = Namespace.DEFAULT(),
                    serverHash = it,
                    serverTimestampMs = serverTimestampMs,
                    serverExpiryMs = serverExpiryMs,
                )
            },
        ).also { result ->
            result.message.serverHash = serverHash
        }
    }

    private fun parseNewerFormatMessage(
        data: ByteArray,
        serverHash: String,
        currentUserId: AccountId,
        serverTimestampMs: Long,
        serverExpiryMs: Long?,
    ): ParseResult {
        val message = UnsupportedMessage(
            kind = UnsupportedMessage.Kind.NEWER_FORMAT,
            placement = UnsupportedMessage.Placement.NONE,
            rawData = data,
            swarmPublicKey = currentUserId.hexString,
            namespace = Namespace.DEFAULT(),
            serverTimestampMs = serverTimestampMs,
            serverExpiryMs = serverExpiryMs,
            syncTarget = null,
        ).apply {
            // The real sender is inside the encryption; the current user stands in so nothing
            // downstream treats it as having come from someone else.
            sender = currentUserId.hexString
            recipient = currentUserId.hexString
            sentTimestamp = serverTimestampMs
            receivedTimestamp = snodeClock.currentTimeMillis()
            isSenderSelf = true
            this.serverHash = serverHash
        }

        if (!message.isValid()) {
            throw NonRetryableException("Invalid message")
        }

        return ParseResult(message = message, proto = SessionProtos.Content.getDefaultInstance(), pro = null)
    }

    fun parseGroupMessage(
        data: ByteArray,
        serverHash: String,
        groupId: AccountId,
        currentUserEd25519PrivKey: ByteArray,
        currentUserId: AccountId,
        serverTimestampMs: Long,
        serverExpiryMs: Long?,
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
            checkForBlockStatus = true,
            isForGroup = true,
            senderIdPrefix = IdPrefix.STANDARD,
            currentUserId = currentUserId,
            currentUserBlindedIDs = emptyList(),
            swarmOrigin = SwarmOrigin(
                rawData = data,
                swarmPublicKey = groupId.hexString,
                namespace = Namespace.GROUP_MESSAGES(),
                serverHash = serverHash,
                serverTimestampMs = serverTimestampMs,
                serverExpiryMs = serverExpiryMs,
            ),
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