package org.session.libsession.messaging.sending_receiving

import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.protocol.SessionProtocol
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.LinkPreview
import org.session.libsession.messaging.messages.visible.Quote
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.CommunityApiRequest
import org.session.libsession.messaging.open_groups.api.SendDirectMessageApi
import org.session.libsession.messaging.open_groups.api.SendMessageApi
import org.session.libsession.messaging.open_groups.api.execute
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos
import org.thoughtcrime.securesms.api.snode.StoreMessageApi
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiRequest
import org.thoughtcrime.securesms.api.swarm.execute
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.pro.copyFromLibSession
import org.thoughtcrime.securesms.service.ExpiringMessageManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview as SignalLinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel as SignalQuote

@Singleton
class MessageSender @Inject constructor(
    private val storage: StorageProtocol,
    private val configFactory: ConfigFactoryProtocol,
    private val recipientRepository: RecipientRepository,
    private val messageDataProvider: MessageDataProvider,
    private val messageSendJobFactory: MessageSendJob.Factory,
    private val messageExpirationManager: ExpiringMessageManager,
    private val snodeClock: SnodeClock,
    private val communityApiExecutor: CommunityApiExecutor,
    private val sendCommunityMessageApiFactory: SendMessageApi.Factory,
    private val sendCommunityDirectMessageApiFactory: SendDirectMessageApi.Factory,
    private val swarmApiExecutor: SwarmApiExecutor,
    private val storeSnodeMessageApiFactory: StoreMessageApi.Factory,
    @param:ManagerScope private val scope: CoroutineScope,
    private val loginStateRepository: LoginStateRepository,
) {

    // Error
    sealed class Error(val description: String, cause: Throwable? = null) : Exception(description, cause) {
        class InvalidMessage : Error("Invalid message.")
        class ProtoConversionFailed(cause: Throwable) : Error("Couldn't convert message to proto.", cause)
        class NoUserED25519KeyPair : Error("Couldn't find user ED25519 key pair.")
        class SigningFailed : Error("Couldn't sign message.")
        class EncryptionFailed : Error("Couldn't encrypt message.")

        // Closed groups
        class InvalidClosedGroupUpdate : Error("Invalid group update.")

        internal val isRetryable: Boolean = when (this) {
            is InvalidMessage, is ProtoConversionFailed, is InvalidClosedGroupUpdate -> false
            else -> true
        }
    }


    private fun SessionProtos.DataMessage.Builder.copyProfileFromConfig() {
        configFactory.withUserConfigs {
            val pic = it.userProfile.getPic()

            profileBuilder.setDisplayName(it.userProfile.getName().orEmpty())
                .setProfilePicture(pic.url)
                .setLastUpdateSeconds(it.userProfile.getProfileUpdatedSeconds())

            setProfileKey(ByteString.copyFrom(pic.keyAsByteArray))
        }
    }

    private fun SessionProtos.MessageRequestResponse.Builder.copyProfileFromConfig() {
        configFactory.withUserConfigs {
            val pic = it.userProfile.getPic()

            profileBuilder.setDisplayName(it.userProfile.getName().orEmpty())
                .setProfilePicture(pic.url)
                .setLastUpdateSeconds(it.userProfile.getProfileUpdatedSeconds())

            setProfileKey(ByteString.copyFrom(pic.keyAsByteArray))
        }
    }

    // Convenience
    suspend fun sendNonDurably(message: Message, destination: Destination, isSyncMessage: Boolean) {
        return if (destination is Destination.OpenGroup || destination is Destination.OpenGroupInbox) {
            sendToOpenGroupDestination(destination, message)
        } else {
            sendToSnodeDestination(destination, message, isSyncMessage)
        }
    }

    private fun buildProto(msg: Message): SessionProtos.Content {
        try {
            val builder = SessionProtos.Content.newBuilder()

            msg.toProto(builder, messageDataProvider)

            // Attach pro proof
            val proProof = configFactory.withUserConfigs { it.userProfile.getProConfig() }?.proProof
            if (proProof != null && proProof.expiryMs > snodeClock.currentTimeMillis()) {
                builder.proMessageBuilder.proofBuilder.copyFromLibSession(proProof)
            } else {
                // If we don't have any valid pro proof, clear the pro message
                builder.clearProMessage()
            }

            // Attach the user's profile if needed
            when {
                builder.hasDataMessage() && !builder.dataMessageBuilder.hasProfile() -> {
                    builder.dataMessageBuilder.copyProfileFromConfig()
                }

                builder.hasMessageRequestResponse() && !builder.messageRequestResponseBuilder.hasProfile() -> {
                    builder.messageRequestResponseBuilder.copyProfileFromConfig()
                }
            }

            return builder.build()
        } catch (e: Exception) {
            throw Error.ProtoConversionFailed(e)
        }
    }

    // One-on-One Chats & Closed Groups
    fun buildWrappedMessageToSnode(destination: Destination, message: Message, isSyncMessage: Boolean): SnodeMessage {
        val userPublicKey = storage.getUserPublicKey()
        val userEd25519PrivKey = requireNotNull(storage.getUserED25519KeyPair()?.secretKey?.data) {
            "Missing user key"
        }
        // Set the timestamp, sender and recipient
        val messageSendTime = snodeClock.currentTimeMillis()
        if (message.sentTimestamp == null) {
            message.sentTimestamp =
                messageSendTime // Visible messages will already have their sent timestamp set
        }

        message.sender = userPublicKey
        // SHARED CONFIG
        when (destination) {
            is Destination.Contact -> message.recipient = destination.publicKey
            is Destination.ClosedGroup -> message.recipient = destination.publicKey
            is Destination.OpenGroup,
            is Destination.OpenGroupInbox -> error("Destination should not be an open group.")
        }

        val isSelfSend = (message.recipient == userPublicKey)
        // Validate the message
        if (!message.isValid()) {
            throw Error.InvalidMessage()
        }
        // Stop here if this is a self-send, unless it's:
        // • a configuration message
        // • a sync message
        // • a closed group control message of type `new`
        if (isSelfSend
            && !isSyncMessage
            && message !is UnsendRequest
        ) {
            throw Error.InvalidMessage()
        }

        val proRotatingEd25519PrivKey = configFactory.withUserConfigs { configs ->
            configs.userProfile.getProConfig()
        }?.rotatingPrivateKey?.data

        val messagePlaintext = buildProto(message).toByteArray()


        val messageContent = when (destination) {
            is Destination.Contact -> {
                SessionProtocol.encodeFor1o1(
                    plaintext = messagePlaintext,
                    myEd25519PrivKey = userEd25519PrivKey,
                    timestampMs = message.sentTimestamp!!,
                    recipientPubKey = Hex.fromStringCondensed(destination.publicKey),
                    proRotatingEd25519PrivKey = proRotatingEd25519PrivKey,
                )
            }

            is Destination.ClosedGroup -> {
                SessionProtocol.encodeForGroup(
                    plaintext = messagePlaintext,
                    myEd25519PrivKey = userEd25519PrivKey,
                    timestampMs = message.sentTimestamp!!,
                    groupEd25519PublicKey = Hex.fromStringCondensed(destination.publicKey),
                    groupEd25519PrivateKey = configFactory.withGroupConfigs(AccountId(destination.publicKey)) {
                        it.groupKeys.groupEncKey()
                    },
                    proRotatingEd25519PrivKey = proRotatingEd25519PrivKey,
                )
            }

            is Destination.OpenGroup,
            is Destination.OpenGroupInbox -> error("Destination should not be an open group.")
        }

        // Send the result
        return SnodeMessage(
            message.recipient!!,
            data = Base64.encodeBytes(messageContent),
            ttl = getSpecifiedTtl(message, isSyncMessage) ?: message.ttl,
            messageSendTime
        )
    }

    // One-on-One Chats & Closed Groups
    private suspend fun sendToSnodeDestination(destination: Destination, message: Message, isSyncMessage: Boolean = false) = supervisorScope {
        // Set the failure handler (need it here already for precondition failure handling)
        fun handleFailure(error: Exception) {
            handleFailedMessageSend(message, error, isSyncMessage)
        }

        try {
            val snodeMessage = buildWrappedMessageToSnode(destination, message, isSyncMessage)
            val sendResult = runCatching {
                when (destination) {
                    is Destination.ClosedGroup -> {
                        val groupAuth = requireNotNull(configFactory.getGroupAuth(AccountId(destination.publicKey))) {
                            "Unable to authorize group message send"
                        }

                        swarmApiExecutor.execute(
                            SwarmApiRequest(
                                swarmPubKeyHex = destination.publicKey,
                                api = storeSnodeMessageApiFactory.create(
                                    message = snodeMessage,
                                    auth = groupAuth,
                                    namespace = Namespace.GROUP_MESSAGES(),
                                )
                            )
                        )
                    }
                    is Destination.Contact -> {
                        swarmApiExecutor.execute(
                            SwarmApiRequest(
                                swarmPubKeyHex = destination.publicKey,
                                api = storeSnodeMessageApiFactory.create(
                                    message = snodeMessage,
                                    auth = null,
                                    namespace = Namespace.DEFAULT()
                                )
                            )
                        )
                    }
                    is Destination.OpenGroup,
                    is Destination.OpenGroupInbox -> throw IllegalStateException("Destination should not be an open group.")
                }
            }


            if (sendResult.isSuccess) {
                message.serverHash = sendResult.getOrThrow().hash
                handleSuccessfulMessageSend(message, destination, isSyncMessage)
            } else {
                throw sendResult.exceptionOrNull()!!
            }
        } catch (exception: Exception) {
            if (exception !is CancellationException) {
                handleFailure(exception)
            }

            throw exception
        }
    }

    private fun getSpecifiedTtl(
        message: Message,
        isSyncMessage: Boolean
    ): Long? {
        // For ClosedGroupControlMessage or GroupUpdateMemberLeftMessage, the expiration timer doesn't apply
        if (message is GroupUpdated && (
                message.inner.hasMemberLeftMessage() ||
                message.inner.hasInviteMessage() ||
                message.inner.hasInviteResponse() ||
                message.inner.hasDeleteMemberContent() ||
                message.inner.hasPromoteMessage())) {
            return null
        }

        // Otherwise the expiration configuration applies
        return message.run {
            (if (isSyncMessage && this is VisibleMessage) syncTarget else recipient)
                ?.let(Address::fromSerialized)
                ?.let(recipientRepository::getRecipientSync)
                ?.expiryMode
                ?.takeIf { it is ExpiryMode.AfterSend || isSyncMessage }
                ?.expiryMillis
                ?.takeIf { it > 0 }
        }
    }

    // Open Groups
    private suspend fun sendToOpenGroupDestination(destination: Destination, message: Message) {
        if (message.sentTimestamp == null) {
            message.sentTimestamp = snodeClock.currentTimeMillis()
        }
        // Attach the blocks message requests info
        configFactory.withUserConfigs { configs ->
            if (message is VisibleMessage) {
                message.blocksMessageRequests = !configs.userProfile.getCommunityMessageRequests()
            }
        }
        var serverCapabilities: List<String>
        var blindedPublicKey: ByteArray? = null
        val loggedInState = loginStateRepository.requireLoggedInState()
        when (destination) {
            is Destination.OpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server).orEmpty()
                storage.getOpenGroupPublicKey(destination.server)?.let {
                    blindedPublicKey = loggedInState
                        .getBlindedKeyPair(serverUrl = destination.server, serverPubKeyHex = it)
                        .pubKey.data
                }
            }
            is Destination.OpenGroupInbox -> {
                serverCapabilities = storage.getServerCapabilities(destination.server).orEmpty()
                blindedPublicKey = loggedInState
                    .getBlindedKeyPair(serverUrl = destination.server,
                        serverPubKeyHex = destination.serverPublicKey)
                    .pubKey.data
            }

            is Destination.ClosedGroup,
            is Destination.Contact -> error("Destination must be an open group.")
        }
        val messageSender = if (serverCapabilities.contains(Capability.BLIND.name.lowercase()) && blindedPublicKey != null) {
            AccountId(IdPrefix.BLINDED, blindedPublicKey).hexString
        } else {
            AccountId(IdPrefix.UN_BLINDED, loggedInState.accountEd25519KeyPair.pubKey.data).hexString
        }
        message.sender = messageSender

        try {
            val content = buildProto(message)

            when (destination) {
                is Destination.OpenGroup -> {
                    val whisperMods = if (destination.whisperTo.isEmpty() && destination.whisperMods) "mods" else null
                    message.recipient = "${destination.server}.${destination.roomToken}.${destination.whisperTo}.$whisperMods"
                    // Validate the message
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage()
                    }
                    val plaintext = SessionProtocol.encodeForCommunity(
                        plaintext = content.toByteArray(),
                        proRotatingEd25519PrivKey = configFactory.withUserConfigs { configs ->
                            configs.userProfile.getProConfig()
                        }?.rotatingPrivateKey?.data,
                    )

                    val openGroupMessage = OpenGroupMessage(
                        sender = message.sender,
                        sentTimestamp = message.sentTimestamp!!,
                        base64EncodedData = Base64.encodeBytes(plaintext),
                    )

                    val response = communityApiExecutor.execute(
                        CommunityApiRequest(
                            serverBaseUrl = destination.server,
                            api = sendCommunityMessageApiFactory.create(
                                room = destination.roomToken,
                                message = openGroupMessage,
                                fileIds = destination.fileIds
                            )
                        ),
                    )

                    message.openGroupServerMessageID = response.id
                    handleSuccessfulMessageSend(message, destination, openGroupSentTimestamp = response.postedMills)
                    return
                }
                is Destination.OpenGroupInbox -> {
                    message.recipient = destination.blindedPublicKey
                    // Validate the message
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage()
                    }
                    val ciphertext = SessionProtocol.encodeForCommunityInbox(
                        plaintext = content.toByteArray(),
                        myEd25519PrivKey = loggedInState.accountEd25519KeyPair.secretKey.data,
                        timestampMs = message.sentTimestamp!!,
                        recipientPubKey = Hex.fromStringCondensed(destination.blindedPublicKey),
                        communityServerPubKey = Hex.fromStringCondensed(destination.serverPublicKey),
                        proRotatingEd25519PrivKey = null,
                    )

                    val response = communityApiExecutor.execute(CommunityApiRequest(
                        serverBaseUrl = destination.server,
                        api = sendCommunityDirectMessageApiFactory.create(
                            recipient = destination.blindedPublicKey.toAddress() as Address.Blinded,
                            messageContent = Base64.encodeBytes(ciphertext)
                        )
                    ))

                    message.openGroupServerMessageID = response.id
                    handleSuccessfulMessageSend(message, destination,
                        openGroupSentTimestamp = response.postedAt?.toEpochMilli() ?: 0L)
                    return
                }
                else -> throw IllegalStateException("Invalid destination.")
            }
        } catch (exception: Exception) {
            if (exception !is CancellationException) handleFailedMessageSend(message, exception)
            throw exception
        }
    }

    // Result Handling
    private fun handleSuccessfulMessageSend(message: Message, destination: Destination, isSyncMessage: Boolean = false, openGroupSentTimestamp: Long = -1) {
        val userPublicKey = storage.getUserPublicKey()!!
        // Ignore future self-sends
        storage.addReceivedMessageTimestamp(message.sentTimestamp!!)
        message.id?.let { messageId ->
            if (openGroupSentTimestamp != -1L && message is VisibleMessage) {
                storage.addReceivedMessageTimestamp(openGroupSentTimestamp)
                message.sentTimestamp = openGroupSentTimestamp
            }

            // When the sync message is successfully sent, the hash value of this TSOutgoingMessage
            // will be replaced by the hash value of the sync message. Since the hash value of the
            // real message has no use when we delete a message. It is OK to let it be.
            message.serverHash?.let {
                storage.setMessageServerHash(messageId, it)
            }

            // in case any errors from previous sends
            storage.clearErrorMessage(messageId)

            // Track the open group server message ID
            val messageIsAddressedToCommunity = message.openGroupServerMessageID != null && (destination is Destination.OpenGroup)
            if (messageIsAddressedToCommunity) {
                val address = Address.Community(destination.server, destination.roomToken)
                val communityThreadID = storage.getThreadId(address)
                if (communityThreadID != null && communityThreadID >= 0) {
                    storage.setOpenGroupServerMessageID(
                        messageID = messageId,
                        serverID = message.openGroupServerMessageID!!,
                        threadID = communityThreadID
                    )
                }
            }

            // Mark the message as sent.
            storage.markAsSent(messageId)

            // Update the message sent timestamp
            storage.updateSentTimestamp(messageId, message.sentTimestamp!!)

            // Start the disappearing messages timer if needed
            messageExpirationManager.onMessageSent(message)
        } ?: run {
            storage.updateReactionIfNeeded(message, message.sender?:userPublicKey, openGroupSentTimestamp)
        }
        // Sync the message if:
        // • the destination was a contact
        // • we didn't sync it already
        // • the message is NOT a DataExtractionNotification
        if (destination is Destination.Contact && !isSyncMessage && message !is DataExtractionNotification) {
            if (message is VisibleMessage) message.syncTarget = destination.publicKey
            if (message is ExpirationTimerUpdate) message.syncTarget = destination.publicKey

            message.id?.let(storage::markAsSyncing)
            scope.launch {
                try {
                    sendToSnodeDestination(Destination.Contact(userPublicKey), message, true)
                } catch (ec: Exception) {
                    Log.e("MessageSender", "Unable to send sync message", ec)
                }
            }
        }
    }

    fun handleFailedMessageSend(message: Message, error: Exception, isSyncMessage: Boolean = false) {
        val messageId = message.id ?: return

        // no need to handle if message is marked as deleted
        if (messageDataProvider.isDeletedMessage(messageId)){
            return
        }

        if (isSyncMessage) storage.markAsSyncFailed(messageId, error)
        else storage.markAsSentFailed(messageId, error)
    }

    // Convenience
    fun send(message: VisibleMessage, address: Address, quote: SignalQuote?, linkPreview: SignalLinkPreview?) {
        val messageId = message.id
        if (messageId?.mms == true) {
            message.attachmentIDs.addAll(messageDataProvider.getAttachmentIDsFor(messageId.id))
        }
        message.quote = Quote.from(quote)
        message.linkPreview = LinkPreview.from(linkPreview)
        message.linkPreview?.let { linkPreview ->
            if (linkPreview.attachmentID == null && messageId?.mms == true) {
                messageDataProvider.getLinkPreviewAttachmentIDFor(messageId.id)?.let { attachmentID ->
                    linkPreview.attachmentID = attachmentID
                    message.attachmentIDs.remove(attachmentID)
                }
            }
        }
        send(message, address)
    }

    @JvmOverloads
    fun send(message: Message, address: Address, statusCallback: SendChannel<Result<Unit>>? = null) {
        val threadID = storage.getThreadId(address)
        message.applyExpiryMode(address)
        message.threadID = threadID
        val destination = Destination.from(address)
        val job = messageSendJobFactory.create(message, destination, statusCallback)
        JobQueue.shared.add(job)

        // if we are sending a 'Note to Self' make sure it is not hidden
        if( message is VisibleMessage &&
            address.toString() == storage.getUserPublicKey() &&
            // only show the NTS if it is currently marked as hidden
            configFactory.withUserConfigs { it.userProfile.getNtsPriority() == PRIORITY_HIDDEN }
        ){
            // update config in case it was marked as hidden there
            configFactory.withMutableUserConfigs {
                it.userProfile.setNtsPriority(PRIORITY_VISIBLE)
            }
        }
    }

    suspend fun sendAndAwait(message: Message, address: Address) {
        val resultChannel = Channel<Result<Unit>>()
        send(message, address, resultChannel)
        resultChannel.receive().getOrThrow()
    }

    suspend fun sendNonDurably(message: Message, address: Address, isSyncMessage: Boolean) {
        val threadID = storage.getThreadId(address)
        message.threadID = threadID
        val destination = Destination.from(address)
        sendNonDurably(message, destination, isSyncMessage)
    }
}