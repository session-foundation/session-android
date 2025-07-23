package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.ExpiryMode
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.LinkPreview
import org.session.libsession.messaging.messages.visible.Quote
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SnodeModule
import org.session.libsession.snode.utilities.asyncPromise
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsignal.crypto.PushTransportDetails
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.defaultRequiresAuth
import org.session.libsignal.utilities.hasNamespaces
import org.session.libsignal.utilities.hexEncodedPublicKey
import java.util.concurrent.TimeUnit
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview as SignalLinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel as SignalQuote

object MessageSender {

    // Error
    sealed class Error(val description: String) : Exception(description) {
        object InvalidMessage : Error("Invalid message.")
        object ProtoConversionFailed : Error("Couldn't convert message to proto.")
        object NoUserED25519KeyPair : Error("Couldn't find user ED25519 key pair.")
        object SigningFailed : Error("Couldn't sign message.")
        object EncryptionFailed : Error("Couldn't encrypt message.")
        data class InvalidDestination(val destination: Destination): Error("Can't send this way to $destination")

        // Closed groups
        object NoThread : Error("Couldn't find a thread associated with the given group public key.")
        object NoKeyPair: Error("Couldn't find a private key associated with the given group public key.")
        object InvalidClosedGroupUpdate : Error("Invalid group update.")

        internal val isRetryable: Boolean = when (this) {
            is InvalidMessage, ProtoConversionFailed, InvalidClosedGroupUpdate -> false
            else -> true
        }
    }

    // Convenience
    fun sendNonDurably(message: Message, destination: Destination, isSyncMessage: Boolean): Promise<Unit, Exception> {
        return if (destination is Destination.LegacyOpenGroup || destination is Destination.OpenGroup || destination is Destination.OpenGroupInbox) {
            sendToOpenGroupDestination(destination, message)
        } else {
            GlobalScope.asyncPromise {
                sendToSnodeDestination(destination, message, isSyncMessage)
            }
        }
    }

    // One-on-One Chats & Closed Groups
    @Throws(Exception::class)
    fun buildWrappedMessageToSnode(destination: Destination, message: Message, isSyncMessage: Boolean): SnodeMessage {
        val storage = MessagingModuleConfiguration.shared.storage
        val configFactory = MessagingModuleConfiguration.shared.configFactory
        val userPublicKey = storage.getUserPublicKey()
        // Set the timestamp, sender and recipient
        val messageSendTime = nowWithOffset
        if (message.sentTimestamp == null) {
            message.sentTimestamp =
                messageSendTime // Visible messages will already have their sent timestamp set
        }

        message.sender = userPublicKey
        // SHARED CONFIG
        when (destination) {
            is Destination.Contact -> message.recipient = destination.publicKey
            is Destination.LegacyClosedGroup -> message.recipient = destination.groupPublicKey
            is Destination.ClosedGroup -> message.recipient = destination.publicKey
            else -> throw IllegalStateException("Destination should not be an open group.")
        }

        val isSelfSend = (message.recipient == userPublicKey)
        // Validate the message
        if (!message.isValid()) {
            throw Error.InvalidMessage
        }
        // Stop here if this is a self-send, unless it's:
        // • a configuration message
        // • a sync message
        // • a closed group control message of type `new`
        if (isSelfSend
            && !isSyncMessage
            && message !is UnsendRequest
        ) {
            throw Error.InvalidMessage
        }
        // Attach the user's profile if needed
        if (message is VisibleMessage) {
            message.profile = storage.getUserProfile()
        }
        if (message is MessageRequestResponse) {
            message.profile = storage.getUserProfile()
        }
        // Convert it to protobuf
        val proto = message.toProto()?.toBuilder() ?: throw Error.ProtoConversionFailed
        if (message is GroupUpdated) {
            if (message.profile != null) {
                proto.mergeDataMessage(message.profile.toProto())
            }
        }

        // Set the timestamp on the content so it can be verified against envelope timestamp
        proto.setSigTimestamp(message.sentTimestamp!!)

        // Serialize the protobuf
        val plaintext = PushTransportDetails.getPaddedMessageBody(proto.build().toByteArray())

        // Envelope information
        val kind: SignalServiceProtos.Envelope.Type
        val senderPublicKey: String
        when (destination) {
            is Destination.Contact -> {
                kind = SignalServiceProtos.Envelope.Type.SESSION_MESSAGE
                senderPublicKey = ""
            }
            is Destination.LegacyClosedGroup -> {
                kind = SignalServiceProtos.Envelope.Type.CLOSED_GROUP_MESSAGE
                senderPublicKey = destination.groupPublicKey
            }
            is Destination.ClosedGroup -> {
                kind = SignalServiceProtos.Envelope.Type.CLOSED_GROUP_MESSAGE
                senderPublicKey = destination.publicKey
            }
            else -> throw IllegalStateException("Destination should not be open group.")
        }

        // Encrypt the serialized protobuf
        val ciphertext = when (destination) {
            is Destination.Contact -> MessageEncrypter.encrypt(plaintext, destination.publicKey)
            is Destination.LegacyClosedGroup -> {
                val encryptionKeyPair =
                    MessagingModuleConfiguration.shared.storage.getLatestClosedGroupEncryptionKeyPair(
                        destination.groupPublicKey
                    )!!
                MessageEncrypter.encrypt(plaintext, encryptionKeyPair.hexEncodedPublicKey)
            }
            is Destination.ClosedGroup -> {
                val envelope = MessageWrapper.createEnvelope(kind, message.sentTimestamp!!, senderPublicKey, proto.build().toByteArray())
                configFactory.withGroupConfigs(AccountId(destination.publicKey)) {
                    it.groupKeys.encrypt(envelope.toByteArray())
                }
            }
            else -> throw IllegalStateException("Destination should not be open group.")
        }
        // Wrap the result using envelope information
        val wrappedMessage = when (destination) {
            is Destination.ClosedGroup -> {
                // encrypted bytes from the above closed group encryption and envelope steps
                ciphertext
            }
            else -> MessageWrapper.wrap(kind, message.sentTimestamp!!, senderPublicKey, ciphertext)
        }
        val base64EncodedData = Base64.encodeBytes(wrappedMessage)
        // Send the result
        return SnodeMessage(
            message.recipient!!,
            base64EncodedData,
            ttl = getSpecifiedTtl(message, isSyncMessage) ?: message.ttl,
            messageSendTime
        )
    }

    // One-on-One Chats & Closed Groups
    private suspend fun sendToSnodeDestination(destination: Destination, message: Message, isSyncMessage: Boolean = false) = supervisorScope {
        val storage = MessagingModuleConfiguration.shared.storage
        val configFactory = MessagingModuleConfiguration.shared.configFactory
        val userPublicKey = storage.getUserPublicKey()

        // recipient will be set later, so initialize it as a function here
        val isSelfSend = { message.recipient == userPublicKey }

        // Set the failure handler (need it here already for precondition failure handling)
        fun handleFailure(error: Exception) {
            handleFailedMessageSend(message, error, isSyncMessage)
            if (destination is Destination.Contact && message is VisibleMessage && !isSelfSend()) {
                SnodeModule.shared.broadcaster.broadcast("messageFailed", message.sentTimestamp!!)
            }
        }

        try {
            val snodeMessage = buildWrappedMessageToSnode(destination, message, isSyncMessage)
            // TODO: this might change in future for config messages
            val forkInfo = SnodeAPI.forkInfo
            val namespaces: List<Int> = when {
                destination is Destination.LegacyClosedGroup
                        && forkInfo.defaultRequiresAuth() -> listOf(Namespace.UNAUTHENTICATED_CLOSED_GROUP())

                destination is Destination.LegacyClosedGroup
                        && forkInfo.hasNamespaces() -> listOf(
                    Namespace.UNAUTHENTICATED_CLOSED_GROUP(),
                    Namespace.DEFAULT
                ())
                destination is Destination.ClosedGroup -> listOf(Namespace.GROUP_MESSAGES())

                else -> listOf(Namespace.DEFAULT())
            }

            val sendTasks = namespaces.map { namespace ->
                if (destination is Destination.ClosedGroup) {
                    val groupAuth = requireNotNull(configFactory.getGroupAuth(AccountId(destination.publicKey))) {
                        "Unable to authorize group message send"
                    }

                    async {
                        SnodeAPI.sendMessage(
                            auth = groupAuth,
                            message = snodeMessage,
                            namespace = namespace,
                        )
                    }
                } else {
                    async {
                        SnodeAPI.sendMessage(snodeMessage, auth = null, namespace = namespace)
                    }
                }
            }

            val sendTaskResults = sendTasks.map {
                runCatching { it.await() }
            }

            val firstSuccess = sendTaskResults.firstOrNull { it.isSuccess }?.getOrNull()

            if (firstSuccess != null) {
                message.serverHash = firstSuccess.hash
                handleSuccessfulMessageSend(message, destination, isSyncMessage)
            } else {
                // If all tasks failed, throw the first exception
                throw sendTaskResults.first().exceptionOrNull()!!
            }
        } catch (exception: Exception) {
            handleFailure(exception)
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
            threadID ?: (if (isSyncMessage && this is VisibleMessage) syncTarget else recipient)
                ?.let(Address.Companion::fromSerialized)
                ?.let(MessagingModuleConfiguration.shared.storage::getThreadId)
        }
            ?.let(MessagingModuleConfiguration.shared.storage::getExpirationConfiguration)
            ?.takeIf { it.isEnabled }
            ?.expiryMode
            ?.takeIf { it is ExpiryMode.AfterSend || isSyncMessage }
            ?.expiryMillis
    }

    // Open Groups
    private fun sendToOpenGroupDestination(destination: Destination, message: Message): Promise<Unit, Exception> {
        val deferred = deferred<Unit, Exception>()
        val storage = MessagingModuleConfiguration.shared.storage
        val configFactory = MessagingModuleConfiguration.shared.configFactory
        if (message.sentTimestamp == null) {
            message.sentTimestamp = nowWithOffset
        }
        // Attach the blocks message requests info
        configFactory.withUserConfigs { configs ->
            if (message is VisibleMessage) {
                message.blocksMessageRequests = !configs.userProfile.getCommunityMessageRequests()
            }
        }
        val userEdKeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()!!
        var serverCapabilities = listOf<String>()
        var blindedPublicKey: ByteArray? = null
        when(destination) {
            is Destination.OpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                storage.getOpenGroup(destination.roomToken, destination.server)?.let {
                    blindedPublicKey = BlindKeyAPI.blind15KeyPairOrNull(
                        ed25519SecretKey = userEdKeyPair.secretKey.data,
                        serverPubKey = Hex.fromStringCondensed(it.publicKey),
                    )?.pubKey?.data
                }
            }
            is Destination.OpenGroupInbox -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                blindedPublicKey = BlindKeyAPI.blind15KeyPairOrNull(
                    ed25519SecretKey = userEdKeyPair.secretKey.data,
                    serverPubKey = Hex.fromStringCondensed(destination.serverPublicKey),
                )?.pubKey?.data
            }
            is Destination.LegacyOpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                storage.getOpenGroup(destination.roomToken, destination.server)?.let {
                    blindedPublicKey = BlindKeyAPI.blind15KeyPairOrNull(
                        ed25519SecretKey = userEdKeyPair.secretKey.data,
                        serverPubKey = Hex.fromStringCondensed(it.publicKey),
                    )?.pubKey?.data
                }
            }
            else -> {}
        }
        val messageSender = if (serverCapabilities.contains(Capability.BLIND.name.lowercase()) && blindedPublicKey != null) {
            AccountId(IdPrefix.BLINDED, blindedPublicKey!!).hexString
        } else {
            AccountId(IdPrefix.UN_BLINDED, userEdKeyPair.pubKey.data).hexString
        }
        message.sender = messageSender
        // Set the failure handler (need it here already for precondition failure handling)
        fun handleFailure(error: Exception) {
            handleFailedMessageSend(message, error)
            deferred.reject(error)
        }
        try {
            // Attach the user's profile if needed
            if (message is VisibleMessage) {
                message.profile = storage.getUserProfile()
            }
            val content = message.toProto()!!.toBuilder()
                .setSigTimestamp(message.sentTimestamp!!)
                .build()

            when (destination) {
                is Destination.OpenGroup -> {
                    val whisperMods = if (destination.whisperTo.isNullOrEmpty() && destination.whisperMods) "mods" else null
                    message.recipient = "${destination.server}.${destination.roomToken}.${destination.whisperTo}.$whisperMods"
                    // Validate the message
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage
                    }
                    val messageBody = content.toByteArray()
                    val plaintext = PushTransportDetails.getPaddedMessageBody(messageBody)
                    val openGroupMessage = OpenGroupMessage(
                        sender = message.sender,
                        sentTimestamp = message.sentTimestamp!!,
                        base64EncodedData = Base64.encodeBytes(plaintext),
                    )
                    OpenGroupApi.sendMessage(openGroupMessage, destination.roomToken, destination.server, destination.whisperTo, destination.whisperMods, destination.fileIds).success {
                        message.openGroupServerMessageID = it.serverID
                        handleSuccessfulMessageSend(message, destination, openGroupSentTimestamp = it.sentTimestamp)
                        deferred.resolve(Unit)
                    }.fail {
                        handleFailure(it)
                    }
                }
                is Destination.OpenGroupInbox -> {
                    message.recipient = destination.blindedPublicKey
                    // Validate the message
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage
                    }
                    val messageBody = content.toByteArray()
                    val plaintext = PushTransportDetails.getPaddedMessageBody(messageBody)
                    val ciphertext = MessageEncrypter.encryptBlinded(
                        plaintext,
                        destination.blindedPublicKey,
                        destination.serverPublicKey
                    )
                    val base64EncodedData = Base64.encodeBytes(ciphertext)
                    OpenGroupApi.sendDirectMessage(base64EncodedData, destination.blindedPublicKey, destination.server).success {
                        message.openGroupServerMessageID = it.id
                        handleSuccessfulMessageSend(message, destination, openGroupSentTimestamp = TimeUnit.SECONDS.toMillis(it.postedAt))
                        deferred.resolve(Unit)
                    }.fail {
                        handleFailure(it)
                    }
                }
                else -> throw IllegalStateException("Invalid destination.")
            }
        } catch (exception: Exception) {
            handleFailure(exception)
        }
        return deferred.promise
    }

    // Result Handling
    fun handleSuccessfulMessageSend(message: Message, destination: Destination, isSyncMessage: Boolean = false, openGroupSentTimestamp: Long = -1) {
        val storage = MessagingModuleConfiguration.shared.storage
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
            val messageIsAddressedToCommunity = message.openGroupServerMessageID != null && (destination is Destination.LegacyOpenGroup || destination is Destination.OpenGroup)
            if (messageIsAddressedToCommunity) {
                val server: String
                val room: String
                when (destination) {
                    is Destination.LegacyOpenGroup -> {
                        server = destination.server
                        room = destination.roomToken
                    }
                    is Destination.OpenGroup -> {
                        server = destination.server
                        room = destination.roomToken
                    }
                    else -> {
                        throw Exception("Destination was a different destination than we were expecting")
                    }
                }
                val encoded = GroupUtil.getEncodedOpenGroupID("$server.$room".toByteArray())
                val communityThreadID = storage.getThreadId(Address.fromSerialized(encoded))
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
            SSKEnvironment.shared.messageExpirationManager.onMessageSent(message)
        } ?: run {
            storage.updateReactionIfNeeded(message, message.sender?:userPublicKey, openGroupSentTimestamp)
        }
        // Sync the message if:
        // • it's a visible message
        // • the destination was a contact
        // • we didn't sync it already
        if (destination is Destination.Contact && !isSyncMessage) {
            if (message is VisibleMessage) message.syncTarget = destination.publicKey
            if (message is ExpirationTimerUpdate) message.syncTarget = destination.publicKey

            message.id?.let(storage::markAsSyncing)
            GlobalScope.launch {
                try {
                    sendToSnodeDestination(Destination.Contact(userPublicKey), message, true)
                } catch (ec: Exception) {
                    Log.e("MessageSender", "Unable to send sync message", ec)
                }
            }
        }
    }

    fun handleFailedMessageSend(message: Message, error: Exception, isSyncMessage: Boolean = false) {
        val storage = MessagingModuleConfiguration.shared.storage

        val messageId = message.id ?: return

        // no need to handle if message is marked as deleted
        if(MessagingModuleConfiguration.shared.messageDataProvider.isDeletedMessage(messageId)){
            return
        }

        if (isSyncMessage) storage.markAsSyncFailed(messageId, error)
        else storage.markAsSentFailed(messageId, error)
    }

    // Convenience
    @JvmStatic
    fun send(message: VisibleMessage, address: Address, quote: SignalQuote?, linkPreview: SignalLinkPreview?) {
        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
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

    @JvmStatic
    @JvmOverloads
    fun send(message: Message, address: Address, statusCallback: SendChannel<Result<Unit>>? = null) {
        val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address)
        threadID?.let(message::applyExpiryMode)
        message.threadID = threadID
        val destination = Destination.from(address)
        val job = MessageSendJob(message, destination, statusCallback)
        JobQueue.shared.add(job)

        // if we are sending a 'Note to Self' make sure it is not hidden
        if( message is VisibleMessage &&
            address.toString() == MessagingModuleConfiguration.shared.storage.getUserPublicKey() &&
            // only show the NTS if it is currently marked as hidden
            MessagingModuleConfiguration.shared.configFactory.withUserConfigs { it.userProfile.getNtsPriority() == PRIORITY_HIDDEN }
        ){
            // make sure note to self is not hidden
            MessagingModuleConfiguration.shared.preferences.setHasHiddenNoteToSelf(false)
            // update config in case it was marked as hidden there
            MessagingModuleConfiguration.shared.configFactory.withMutableUserConfigs {
                it.userProfile.setNtsPriority(PRIORITY_VISIBLE)
            }
        }
    }

    suspend fun sendAndAwait(message: Message, address: Address) {
        val resultChannel = Channel<Result<Unit>>()
        send(message, address, resultChannel)
        resultChannel.receive().getOrThrow()
    }

    fun sendNonDurably(message: Message, address: Address, isSyncMessage: Boolean): Promise<Unit, Exception> {
        val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address)
        message.threadID = threadID
        val destination = Destination.from(address)
        return sendNonDurably(message, destination, isSyncMessage)
    }
}