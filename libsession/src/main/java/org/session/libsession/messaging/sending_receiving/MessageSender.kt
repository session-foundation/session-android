package org.session.libsession.messaging.sending_receiving

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import network.loki.mesenger.KeysRepository
import network.loki.mesenger.RawKeyEncryptDecryptHelper
import network.loki.messenger.libsession_util.util.ExpiryMode
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.jobs.NotifyPNServerJob
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.control.*
import org.session.libsession.messaging.messages.visible.LinkPreview
import org.session.libsession.messaging.messages.visible.Quote
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.utilities.AccountId
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.RawResponsePromise
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SnodeModule
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsignal.crypto.PushTransportDetails
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Alias para Adjuntos / LinkPreview / Quote
import org.session.libsession.messaging.sending_receiving.attachments.Attachment as SignalAttachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview as SignalLinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel as SignalQuote

/**
 * MessageSender
 * Encargado de construir y enviar (E2E) los mensajes a snodes
 * o a open groups.
 */
object MessageSender {

    sealed class Error(val description: String) : Exception(description) {
        object InvalidMessage : Error("Invalid message.")
        object ProtoConversionFailed : Error("Couldn't convert message to proto.")
        object NoUserED25519KeyPair : Error("Couldn't find user ED25519 key pair.")
        object SigningFailed : Error("Couldn't sign message.")
        object EncryptionFailed : Error("Couldn't encrypt message.")
        object NoThread : Error("Couldn't find a thread associated with the given group public key.")
        object NoKeyPair : Error("Couldn't find a private key associated with the given group public key.")
        object InvalidClosedGroupUpdate : Error("Invalid group update.")

        internal val isRetryable: Boolean
            get() = when (this) {
                is InvalidMessage, ProtoConversionFailed, InvalidClosedGroupUpdate -> false
                else -> true
            }
    }

    // ------------------------------------------------------------------------
    //  MÉTODO PRINCIPAL
    // ------------------------------------------------------------------------
    fun send(
        message: Message,
        destination: Destination,
        isSyncMessage: Boolean
    ): Promise<Unit, Exception> {
        Log.d("MessageSender", "send() => message.id=${message.id}, destination=$destination, isSync=$isSyncMessage")

        // Actualizar cache si es VisibleMessage
        if (message is VisibleMessage) {
            MessagingModuleConfiguration.shared.lastSentTimestampCache
                .submitTimestamp(message.threadID!!, message.sentTimestamp!!)
        }

        // Decidir si es open-group o snode
        return if (
            destination is Destination.LegacyOpenGroup ||
            destination is Destination.OpenGroup ||
            destination is Destination.OpenGroupInbox
        ) {
            sendToOpenGroupDestination(destination, message)
        } else {
            sendToSnodeDestination(destination, message, isSyncMessage)
        }
    }

    // ------------------------------------------------------------------------
    //  buildConfigMessageToSnode => para SharedConfigurationMessage
    // ------------------------------------------------------------------------
    fun buildConfigMessageToSnode(destinationPubKey: String, message: SharedConfigurationMessage): SnodeMessage {
        Log.d("MessageSender", "buildConfigMessageToSnode(): for SharedConfigurationMessage => pubKey=$destinationPubKey")
        val base64Data = Base64.encodeBytes(message.data)
        val msgTimestamp = SnodeAPI.nowWithOffset
        return SnodeMessage(
            destinationPubKey,
            base64Data,
            message.ttl,
            msgTimestamp
        )
    }

    /**
     * Construye un SnodeMessage con cifrado E2E a partir de un [Message].
     *   * Aplica cifrado por hilo (si hay threadKeyAlias).
     *   * Aplica cifrado universal (si está habilitado).
     *   * Llama a [MessageWrapper.wrap(...)] para crear un Envelope con type/timestamp/source/thread_alias.
     *     - En 1:1 forzamos Envelope.source = userPublicKey
     * Luego lo base64-encodea y arma [SnodeMessage].
     */
    @Throws(Exception::class)
    fun buildWrappedMessageToSnode(
        destination: Destination,
        message: Message,
        isSyncMessage: Boolean
    ): SnodeMessage {
        Log.d("MessageSender", "buildWrappedMessageToSnode() => msg.id=${message.id}, isSync=$isSyncMessage")

        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey() ?: throw Error.InvalidMessage

        // Aseguramos timestamps
        val messageSendTime = nowWithOffset
        if (message.sentTimestamp == null) {
            message.sentTimestamp = messageSendTime
        }
        message.sender = userPublicKey

        // Ajustar recipient según Destination
        when (destination) {
            is Destination.Contact -> message.recipient = destination.publicKey
            is Destination.ClosedGroup -> message.recipient = destination.groupPublicKey
            else -> throw IllegalStateException("buildWrappedMessageToSnode no aplica a open group.")
        }

        // Validar
        if (!message.isValid()) {
            throw Error.InvalidMessage
        }

        // Asignar userProfile si es VisibleMessage (o request response)
        if (message is VisibleMessage) {
            message.profile = storage.getUserProfile()
        }
        if (message is MessageRequestResponse) {
            message.profile = storage.getUserProfile()
        }

        // (1) Generar proto principal
        val proto = message.toProto() ?: throw Error.ProtoConversionFailed

        // (2) Padd + cifrar (thread + universal)
        var plaintext = PushTransportDetails.getPaddedMessageBody(proto.toByteArray())

        val threadCiphered = applyThreadEncryptionIfEnabled(plaintext, message.threadID)
        if (threadCiphered != null) {
            plaintext = threadCiphered
        } else {
            applyUniversalConvEncryptionIfEnabled(plaintext)?.let {
                plaintext = it
            }
        }

        // (3) Cifrado E2E X25519
        val ciphertext = when (destination) {
            is Destination.Contact -> {
                MessageEncrypter.encrypt(plaintext, destination.publicKey)
            }
            is Destination.ClosedGroup -> {
                val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(destination.groupPublicKey)
                    ?: throw Error.NoKeyPair
                MessageEncrypter.encrypt(plaintext, encryptionKeyPair.hexEncodedPublicKey)
            }
            else -> throw IllegalStateException("Invalid destination for snode.")
        }

        // (4) Decidir Envelope.Type
        val envelopeType = when (destination) {
            is Destination.Contact -> SignalServiceProtos.Envelope.Type.SESSION_MESSAGE
            is Destination.ClosedGroup -> SignalServiceProtos.Envelope.Type.CLOSED_GROUP_MESSAGE
            else -> throw IllegalStateException("Invalid destination for snode.")
        }

        // (4b) **Fuente** del envelope:
        //     - Si es 1:1 => userPublicKey (forzamos que NUNCA sea "")
        //     - Si es closedGroup => groupPublicKey
        val senderPubKeyForEnvelope = when (destination) {
            is Destination.Contact     -> userPublicKey
            is Destination.ClosedGroup -> destination.groupPublicKey
            else -> ""
        }

        // (5) threadKeyAlias si existe
        val alias = message.threadID?.let { tid ->
            if (tid >= 0) storage.getThreadKeyAlias(tid) else null
        }

        Log.d(
            "MessageSender",
            "buildWrappedMessageToSnode() => envelopeType=$envelopeType, " +
                    "sentTs=${message.sentTimestamp}, source=$senderPubKeyForEnvelope, " +
                    "threadAlias=$alias"
        )

        // (6) Llamamos a MessageWrapper.wrap(...)
        val finalBytes = MessageWrapper.wrap(
            type = envelopeType,
            timestamp = message.sentTimestamp!!,
            senderPublicKey = senderPubKeyForEnvelope,
            content = ciphertext,
            threadKeyAlias = alias
        )

        // (7) Base64-encode final
        val base64EncodedData = Base64.encodeBytes(finalBytes)

        // (8) Retornar SnodeMessage
        return SnodeMessage(
            message.recipient!!,
            base64EncodedData,
            ttl = getSpecifiedTtl(message, isSyncMessage) ?: message.ttl,
            timestamp = messageSendTime
        )
    }

    /**
     * Cifrado universal (AES-GCM) si está habilitado
     */
    private fun applyUniversalConvEncryptionIfEnabled(plaintext: ByteArray): ByteArray? {
        val context = MessagingModuleConfiguration.shared.context
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val sharedPrefs = EncryptedSharedPreferences.create(
            context,
            "encryption_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val enabled = sharedPrefs.getBoolean("encryption_enabled_universalConv", false)
        val alias   = sharedPrefs.getString("encryption_key_alias_universalConv", "") ?: ""

        if (!enabled || alias.isEmpty()) {
            Log.d("MessageSender", "applyUniversalConvEncryptionIfEnabled => disabled or no alias => skip.")
            return null
        }

        val keyItem = KeysRepository.loadKeys(context).find { it.nickname == alias } ?: run {
            Log.d("MessageSender", "applyUniversalConvEncryptionIfEnabled => key not found => skip.")
            return null
        }

        val rawKeyBytes = android.util.Base64.decode(keyItem.secret, android.util.Base64.NO_WRAP)
        val cipherBytes = RawKeyEncryptDecryptHelper.encryptBytesAESWithRawKey(plaintext, rawKeyBytes)
        Log.d("MessageSender", "applyUniversalConvEncryptionIfEnabled => new size=${cipherBytes.size}")
        return cipherBytes
    }

    /**
     * Cifrado “por hilo” si existe alias asignado al threadId
     */
    private fun applyThreadEncryptionIfEnabled(plaintext: ByteArray, threadId: Long?): ByteArray? {
        if (threadId == null || threadId < 0) return null
        return try {
            val storage = MessagingModuleConfiguration.shared.storage
            val alias = storage.getThreadKeyAlias(threadId)
            if (!alias.isNullOrEmpty()) {
                val ctx = MessagingModuleConfiguration.shared.context
                val keyItem = KeysRepository.loadKeys(ctx).find { it.nickname == alias }
                if (keyItem != null) {
                    val rawKeyBytes = android.util.Base64.decode(keyItem.secret, android.util.Base64.NO_WRAP)
                    val cipherBytes = RawKeyEncryptDecryptHelper.encryptBytesAESWithRawKey(plaintext, rawKeyBytes)
                    Log.d("MessageSender", "applyThreadEncryptionIfEnabled => used alias=$alias for thread=$threadId, size=${cipherBytes.size}")
                    return cipherBytes
                } else null
            } else null
        } catch (e: Exception) {
            Log.w("MessageSender", "applyThreadEncryptionIfEnabled => error: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------------
    //   Envía a snode (1-1 o closed-group)
    // ------------------------------------------------------------------------
    private fun sendToSnodeDestination(
        destination: Destination,
        message: Message,
        isSyncMessage: Boolean
    ): Promise<Unit, Exception> {
        val deferred = deferred<Unit, Exception>()
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()

        fun handleFailure(e: Exception) {
            handleFailedMessageSend(message, e, isSyncMessage)
            if (
                destination is Destination.Contact &&
                message is VisibleMessage &&
                message.recipient != userPublicKey
            ) {
                SnodeModule.shared.broadcaster.broadcast("messageFailed", message.sentTimestamp!!)
            }
            deferred.reject(e)
        }

        try {
            val snodeMessage = buildWrappedMessageToSnode(destination, message, isSyncMessage)
            val forkInfo = SnodeAPI.forkInfo
            val namespaces: List<Int> = when {
                destination is Destination.ClosedGroup && forkInfo.defaultRequiresAuth() ->
                    listOf(Namespace.UNAUTHENTICATED_CLOSED_GROUP)
                destination is Destination.ClosedGroup && forkInfo.hasNamespaces() ->
                    listOf(Namespace.UNAUTHENTICATED_CLOSED_GROUP, Namespace.DEFAULT)
                else -> listOf(Namespace.DEFAULT)
            }

            val promises = namespaces.map { ns ->
                SnodeAPI.sendMessage(snodeMessage, requiresAuth = false, namespace = ns)
            }

            val promiseCount = promises.size
            val errorCount   = AtomicInteger(0)
            var isSuccess    = false

            promises.forEach { rawPromise: RawResponsePromise ->
                rawPromise.success { response ->
                    if (isSuccess) return@success
                    isSuccess = true

                    val hash = response["hash"] as? String
                    message.serverHash = hash
                    handleSuccessfulMessageSend(message, destination, isSyncMessage)

                    val shouldNotify = when (message) {
                        is VisibleMessage, is UnsendRequest -> !isSyncMessage
                        is CallMessage -> (message.type == SignalServiceProtos.CallMessage.Type.PRE_OFFER)
                        else -> false
                    }
                    if (shouldNotify) {
                        val notifyJob = NotifyPNServerJob(snodeMessage)
                        JobQueue.shared.add(notifyJob)
                    }
                    deferred.resolve(Unit)
                }

                rawPromise.fail { e ->
                    val currErrors = errorCount.incrementAndGet()
                    if (currErrors == promiseCount) {
                        handleFailure(e)
                    }
                }
            }

        } catch (ex: Exception) {
            handleFailure(ex)
        }

        return deferred.promise
    }

    // ------------------------------------------------------------------------
    //          TTL / Expiración
    // ------------------------------------------------------------------------
    private fun getSpecifiedTtl(message: Message, isSyncMessage: Boolean): Long? {
        if (message is ClosedGroupControlMessage) return null
        val address: Address? = if (isSyncMessage && message is VisibleMessage) {
            message.syncTarget?.let(Address::fromSerialized)
        } else {
            message.threadID?.let { Address.fromSerialized(it.toString()) }
                ?: message.recipient?.let(Address::fromSerialized)
        }
        val threadID = address?.let { MessagingModuleConfiguration.shared.storage.getThreadId(it) }
        val config   = threadID?.let { MessagingModuleConfiguration.shared.storage.getExpirationConfiguration(it) }

        if (config?.isEnabled == true) {
            val mode = config.expiryMode
            if (mode is ExpiryMode.AfterSend || isSyncMessage) {
                return mode.expiryMillis
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    //  Envía a open-group
    // ------------------------------------------------------------------------
    private fun sendToOpenGroupDestination(
        destination: Destination,
        message: Message
    ): Promise<Unit, Exception> {
        val deferred = deferred<Unit, Exception>()
        val storage  = MessagingModuleConfiguration.shared.storage
        val configF  = MessagingModuleConfiguration.shared.configFactory

        if (message.sentTimestamp == null) {
            message.sentTimestamp = nowWithOffset
        }
        configF.user?.let { user ->
            if (message is VisibleMessage) {
                message.blocksMessageRequests = !user.getCommunityMessageRequests()
            }
        }

        val userEdKeyPair = MessagingModuleConfiguration.shared.getUserED25519KeyPair()
            ?: throw Error.NoUserED25519KeyPair

        var serverCapabilities = listOf<String>()
        var blindedPublicKey: ByteArray? = null
        when (destination) {
            is Destination.OpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                storage.getOpenGroup(destination.roomToken, destination.server)?.let {
                    blindedPublicKey = SodiumUtilities.blindedKeyPair(it.publicKey, userEdKeyPair)?.publicKey?.asBytes
                }
            }
            is Destination.OpenGroupInbox -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                blindedPublicKey = SodiumUtilities.blindedKeyPair(destination.serverPublicKey, userEdKeyPair)?.publicKey?.asBytes
            }
            is Destination.LegacyOpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                storage.getOpenGroup(destination.roomToken, destination.server)?.let {
                    blindedPublicKey = SodiumUtilities.blindedKeyPair(it.publicKey, userEdKeyPair)?.publicKey?.asBytes
                }
            }
            else -> {}
        }

        val senderId = if (serverCapabilities.contains(Capability.BLIND.name.lowercase()) && blindedPublicKey != null) {
            AccountId(IdPrefix.BLINDED, blindedPublicKey!!).hexString
        } else {
            AccountId(IdPrefix.UN_BLINDED, userEdKeyPair.publicKey.asBytes).hexString
        }
        message.sender = senderId

        fun fail(e: Exception) {
            handleFailedMessageSend(message, e)
            deferred.reject(e)
        }

        try {
            if (message is VisibleMessage) {
                message.profile = storage.getUserProfile()
            }

            when (destination) {
                is Destination.OpenGroup -> {
                    val whisperMods = if (destination.whisperTo.isNullOrEmpty() && destination.whisperMods) "mods" else null
                    message.recipient = "${destination.server}.${destination.roomToken}.${destination.whisperTo}.$whisperMods"

                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage
                    }
                    val msgBody   = message.toProto()?.toByteArray() ?: throw Error.ProtoConversionFailed
                    val plaintext = PushTransportDetails.getPaddedMessageBody(msgBody)

                    val ogMsg = OpenGroupMessage(
                        sender = message.sender,
                        sentTimestamp = message.sentTimestamp!!,
                        base64EncodedData = Base64.encodeBytes(plaintext)
                    )
                    OpenGroupApi.sendMessage(
                        ogMsg,
                        destination.roomToken,
                        destination.server,
                        destination.whisperTo,
                        destination.whisperMods,
                        destination.fileIds
                    ).success {
                        message.openGroupServerMessageID = it.serverID
                        handleSuccessfulMessageSend(message, destination, openGroupSentTimestamp = it.sentTimestamp)
                        deferred.resolve(Unit)
                    }.fail {
                        fail(it)
                    }
                }
                is Destination.OpenGroupInbox -> {
                    message.recipient = destination.blindedPublicKey
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage
                    }
                    val msgBody = message.toProto()?.toByteArray() ?: throw Error.ProtoConversionFailed
                    val plaintext = PushTransportDetails.getPaddedMessageBody(msgBody)

                    val ciphertext = MessageEncrypter.encryptBlinded(
                        plaintext,
                        destination.blindedPublicKey,
                        destination.serverPublicKey
                    )
                    val base64EncodedData = Base64.encodeBytes(ciphertext)

                    OpenGroupApi.sendDirectMessage(
                        base64EncodedData,
                        destination.blindedPublicKey,
                        destination.server
                    ).success {
                        message.openGroupServerMessageID = it.id
                        handleSuccessfulMessageSend(
                            message,
                            destination,
                            openGroupSentTimestamp = TimeUnit.SECONDS.toMillis(it.postedAt)
                        )
                        deferred.resolve(Unit)
                    }.fail {
                        fail(it)
                    }
                }
                else -> throw IllegalStateException("Invalid open-group destination.")
            }
        } catch (ex: Exception) {
            fail(ex)
        }
        return deferred.promise
    }

    // ------------------------------------------------------------------------
    //   handleSuccessfulMessageSend / handleFailedMessageSend
    // ------------------------------------------------------------------------
    private fun handleSuccessfulMessageSend(
        message: Message,
        destination: Destination,
        isSyncMessage: Boolean = false,
        openGroupSentTimestamp: Long = -1
    ) {
        val storage = MessagingModuleConfiguration.shared.storage
        val userPub = storage.getUserPublicKey() ?: return
        val ts      = message.sentTimestamp ?: return

        // openGroup => actualiza timestamps
        if (message is VisibleMessage && openGroupSentTimestamp != -1L) {
            MessagingModuleConfiguration.shared.lastSentTimestampCache
                .submitTimestamp(message.threadID!!, openGroupSentTimestamp)
        }
        storage.addReceivedMessageTimestamp(ts)

        val (msgID, isMms) = storage.getMessageIdInDatabase(ts, userPub)
            ?: run {
                // Tal vez es Reaction
                storage.updateReactionIfNeeded(message, message.sender ?: userPub, openGroupSentTimestamp)
                return
            }

        if (openGroupSentTimestamp != -1L && message is VisibleMessage) {
            storage.addReceivedMessageTimestamp(openGroupSentTimestamp)
            storage.updateSentTimestamp(
                msgID,
                message.isMediaMessage(),
                openGroupSentTimestamp,
                message.threadID!!
            )
            message.sentTimestamp = openGroupSentTimestamp
        }
        message.serverHash?.let {
            storage.setMessageServerHash(msgID, isMms, it)
        }
        storage.clearErrorMessage(msgID)

        val isCommunityDest = (
                message.openGroupServerMessageID != null &&
                        (destination is Destination.LegacyOpenGroup || destination is Destination.OpenGroup)
                )
        if (isCommunityDest) {
            val (srv, room) = when (destination) {
                is Destination.LegacyOpenGroup -> destination.server to destination.roomToken
                is Destination.OpenGroup       -> destination.server to destination.roomToken
                else -> throw Exception("Unexpected group type.")
            }
            val encoded = GroupUtil.getEncodedOpenGroupID("$srv.$room".toByteArray())
            val thr = storage.getThreadId(Address.fromSerialized(encoded))
            if (thr != null && thr >= 0) {
                storage.setOpenGroupServerMessageID(
                    msgID,
                    message.openGroupServerMessageID!!,
                    thr,
                    !(message as VisibleMessage).isMediaMessage()
                )
            }
        }

        if (isCommunityDest) {
            storage.markAsSentToCommunity(message.threadID!!, message.id!!)
            storage.markUnidentifiedInCommunity(message.threadID!!, message.id!!)
        } else {
            storage.markAsSent(ts, userPub)
            storage.markUnidentified(ts, userPub)
        }

        SSKEnvironment.shared.messageExpirationManager
            .maybeStartExpiration(message, startDisappearAfterRead = true)

        // si es 1:1 y no isSync => mandar sync
        if (destination is Destination.Contact && !isSyncMessage) {
            if (message is VisibleMessage) {
                message.syncTarget = destination.publicKey
            }
            if (message is ExpirationTimerUpdate) {
                message.syncTarget = destination.publicKey
            }
            storage.markAsSyncing(ts, userPub)
            sendToSnodeDestination(Destination.Contact(userPub), message, true)
        }
    }

    fun handleFailedMessageSend(
        message: Message,
        error: Exception,
        isSyncMessage: Boolean = false
    ) {
        val storage = MessagingModuleConfiguration.shared.storage
        val ts = message.sentTimestamp ?: return
        if (MessagingModuleConfiguration.shared.messageDataProvider.isDeletedMessage(ts)) {
            return
        }
        val userPub = storage.getUserPublicKey() ?: return
        val author  = message.sender ?: userPub

        if (isSyncMessage) {
            storage.markAsSyncFailed(ts, author, error)
        } else {
            storage.markAsSentFailed(ts, author, error)
        }
    }

    // ------------------------------------------------------------------------
    //   SOBRECARGAS: attach + link + quote
    // ------------------------------------------------------------------------
    @JvmStatic
    fun send(
        message: VisibleMessage,
        address: Address,
        attachments: List<SignalAttachment>,
        quoteModel: SignalQuote?,
        linkPreviewModel: SignalLinkPreview?
    ) {
        // 1) Adjuntar attachments
        val msgProvider = MessagingModuleConfiguration.shared.messageDataProvider
        val attachedIDs = msgProvider.getAttachmentIDsFor(message.id!!)
        message.attachmentIDs.addAll(attachedIDs)

        // 2) Quote y linkPreview
        message.quote = Quote.from(quoteModel)
        message.linkPreview = LinkPreview.from(linkPreviewModel)

        // 3) Llamar a la versión base
        send(message, address)
    }

    @JvmStatic
    fun send(message: Message, address: Address) {
        val storage = MessagingModuleConfiguration.shared.storage
        val threadID = storage.getThreadId(address)
        threadID?.let(message::applyExpiryMode)
        message.threadID = threadID

        val destination = Destination.from(address)
        val job = MessageSendJob(message, destination)
        JobQueue.shared.add(job)
    }

    // Versión “nonDurably”
    fun sendNonDurably(
        message: VisibleMessage,
        attachments: List<SignalAttachment>,
        address: Address,
        isSyncMessage: Boolean
    ): Promise<Unit, Exception> {
        val mp = MessagingModuleConfiguration.shared.messageDataProvider
        val attachIDs = mp.getAttachmentIDsFor(message.id!!)
        message.attachmentIDs.addAll(attachIDs)
        return sendNonDurably(message as Message, address, isSyncMessage)
    }

    fun sendNonDurably(
        message: Message,
        address: Address,
        isSyncMessage: Boolean
    ): Promise<Unit, Exception> {
        val storage = MessagingModuleConfiguration.shared.storage
        val threadID = storage.getThreadId(address)
        message.threadID = threadID
        val destination = Destination.from(address)
        return send(message, destination, isSyncMessage)
    }

    // ------------------------------------------------------------------------
    //   Ejemplo: closedGroup
    // ------------------------------------------------------------------------
    fun createClosedGroup(device: Device, name: String, members: Collection<String>): Promise<String, Exception> {
        return create(device, name, members)
    }
    fun explicitNameChange(groupPublicKey: String, newName: String) {
        return setName(groupPublicKey, newName)
    }
    fun explicitAddMembers(groupPublicKey: String, membersToAdd: List<String>) {
        return addMembers(groupPublicKey, membersToAdd)
    }
    fun explicitRemoveMembers(groupPublicKey: String, membersToRemove: List<String>) {
        return removeMembers(groupPublicKey, membersToRemove)
    }
    @JvmStatic
    fun explicitLeave(groupPublicKey: String, notifyUser: Boolean): Promise<Unit, Exception> {
        return leave(groupPublicKey, notifyUser)
    }
}
