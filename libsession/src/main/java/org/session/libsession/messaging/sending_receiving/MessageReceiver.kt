package org.session.libsession.messaging.sending_receiving

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import network.loki.mesenger.KeysRepository
import network.loki.mesenger.RawKeyEncryptDecryptHelper
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.*
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.utilities.AccountId
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.SnodeAPI
import org.session.libsignal.crypto.PushTransportDetails
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsession.utilities.Address

object MessageReceiver {

    internal sealed class Error(message: String) : Exception(message) {
        object DuplicateMessage : Error("Duplicate message.")
        object InvalidMessage : Error("Invalid message.")
        object UnknownMessage : Error("Unknown message type.")
        object UnknownEnvelopeType : Error("Unknown envelope type.")
        object DecryptionFailed : Error("Couldn't decrypt message.")
        object InvalidSignature : Error("Invalid message signature.")
        object NoData : Error("Received an empty envelope.")
        object SenderBlocked : Error("Received a message from a blocked user.")
        object NoThread : Error("Couldn't find thread for message.")
        object SelfSend : Error("Message addressed at self.")
        object InvalidGroupPublicKey : Error("Invalid group public key.")
        object NoGroupThread : Error("No thread exists for this group.")
        object NoGroupKeyPair : Error("Missing group key pair.")
        object NoUserED25519KeyPair : Error("Couldn't find user ED25519 key pair.")
        object ExpiredMessage : Error("Message has already expired, prevent adding.")

        internal val isRetryable: Boolean = when (this) {
            is DuplicateMessage,
            is InvalidMessage,
            is UnknownMessage,
            is UnknownEnvelopeType,
            is InvalidSignature,
            is NoData,
            is SenderBlocked,
            is SelfSend,
            is ExpiredMessage,
            is NoGroupThread -> false
            else -> true
        }
    }

    /**
     * Descifra un Envelope proveniente de la red.
     *  1) Primero intentamos parsear 'data' como un Envelope directo (si no hay WebSocketMessage).
     *  2) Si falla, usamos [MessageWrapper.unwrap(...)] (que parsea WebSocketMessage y extrae Envelope).
     *  3) Luego desciframos la `content`.
     */
    internal fun parse(
        data: ByteArray,
        openGroupServerID: Long?,
        isOutgoing: Boolean? = null,
        otherBlindedPublicKey: String? = null,
        openGroupPublicKey: String? = null,
        currentClosedGroups: Set<String>?
    ): Pair<Message, SignalServiceProtos.Content> {

        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()
        val isOpenGroupMessage = (openGroupServerID != null)

        // 1) Intentar parsear data como un Envelope directo:
        val envelope = try {
            SignalServiceProtos.Envelope.parseFrom(data)
        } catch (ex: Exception) {
            // 2) Fallback: parsear con MessageWrapper.unwrap (WebSocketMessage => Envelope)
            Log.d("MessageReceiver", "Direct parseFrom(Envelope) falló => probamos unwrap. Error: ${ex.message}")
            try {
                MessageWrapper.unwrap(data)
            } catch (ex2: Exception) {
                Log.w("MessageReceiver", "Fallo total parseando Envelope: ${ex2.message}")
                throw Error.NoData
            }
        }

        Log.d(
            "MessageReceiver",
            "parse() => Envelope con type=${envelope.type}, source=${envelope.source}, threadAlias=${
                if (envelope.hasThreadKeyAlias()) envelope.threadKeyAlias else "<none>"
            }"
        )

        val ciphertext = envelope.content ?: throw Error.NoData
        var plaintext: ByteArray? = null
        var sender: String? = if (envelope.hasSource()) envelope.source else null
        var groupPk: String? = null

        // [1] Mensaje abierto => no E2E
        if (isOpenGroupMessage) {
            Log.d("MessageReceiver", "parse() => isOpenGroup => sin E2E (plaintext=ciphertext).")
            plaintext = ciphertext.toByteArray()
        } else {
            // E2E
            when (envelope.type) {
                SignalServiceProtos.Envelope.Type.SESSION_MESSAGE -> {
                    if (!sender.isNullOrEmpty() && IdPrefix.fromValue(sender)?.isBlinded() == true) {
                        Log.d("MessageReceiver", "parse() => It's a BLINDED session message, decryptBlinded omitido.")
                        if (openGroupPublicKey.isNullOrEmpty() || otherBlindedPublicKey.isNullOrEmpty()) {
                            Log.w("MessageReceiver", "No tenemos forma de descifrar blinded sin cross-dep => fallback.")
                        }
                        val userX25519KeyPair = storage.getUserX25519KeyPair()
                        val decRes = MessageDecrypter.decrypt(ciphertext.toByteArray(), userX25519KeyPair)
                        plaintext = decRes.first
                        sender = decRes.second
                    } else {
                        val userX25519KeyPair = storage.getUserX25519KeyPair()
                        val decRes = MessageDecrypter.decrypt(ciphertext.toByteArray(), userX25519KeyPair)
                        plaintext = decRes.first
                        sender = decRes.second
                    }
                }
                SignalServiceProtos.Envelope.Type.CLOSED_GROUP_MESSAGE -> {
                    val hexGroupPk = sender ?: throw Error.InvalidGroupPublicKey
                    Log.d("MessageReceiver", "parse() => It's a CLOSED_GROUP_MESSAGE => hexGroupPk=$hexGroupPk")
                    if (!storage.isClosedGroup(hexGroupPk)) {
                        throw Error.InvalidGroupPublicKey
                    }
                    val keyPairs = storage.getClosedGroupEncryptionKeyPairs(hexGroupPk)
                    if (keyPairs.isEmpty()) {
                        throw Error.NoGroupKeyPair
                    }
                    var kp = keyPairs.removeLast()
                    fun tryDecrypt() {
                        try {
                            val decRes = MessageDecrypter.decrypt(ciphertext.toByteArray(), kp)
                            plaintext = decRes.first
                            sender = decRes.second
                        } catch (e: Exception) {
                            if (keyPairs.isNotEmpty()) {
                                kp = keyPairs.removeLast()
                                tryDecrypt()
                            } else throw e
                        }
                    }
                    groupPk = hexGroupPk
                    tryDecrypt()
                }
                else -> {
                    Log.d("MessageReceiver", "parse() => Unknown envelope type = ${envelope.type}")
                    throw Error.UnknownEnvelopeType
                }
            }
            val currentSender = sender // variable local inmutable
            if (!currentSender.isNullOrEmpty() && isBlocked(currentSender)) {
                throw Error.SenderBlocked
            }
        }

        // 2) Aplicar descifrado por hilo / universal (forzamos currentPlaintext a no nulo)
        if (plaintext != null && !isOpenGroupMessage) {
            var currentPlaintext: ByteArray = plaintext!!  // Forzamos no nulo
            val addressForThread = if (!sender.isNullOrEmpty()) Address.fromSerialized(sender!!) else null
            val maybeThreadId = addressForThread?.let { storage.getThreadId(it) }

            if (maybeThreadId != null && maybeThreadId >= 0) {
                Log.d("MessageReceiver", "Intentando descifrado por hilo => threadId=$maybeThreadId")
                val threadDecrypted = applyThreadDecryptionIfEnabled(currentPlaintext, maybeThreadId)
                if (threadDecrypted != null) {
                    currentPlaintext = threadDecrypted
                    Log.d("MessageReceiver", "Descifrado por hilo exitoso => threadId $maybeThreadId")
                } else {
                    Log.d("MessageReceiver", "Fallo en hilo => probamos universal.")
                    val universalDecrypted = applyUniversalConvDecryptionIfEnabled(currentPlaintext)
                    if (universalDecrypted != null) {
                        currentPlaintext = universalDecrypted
                        Log.d("MessageReceiver", "Descifrado universal tras hilo-fail => success => threadId $maybeThreadId")
                    }
                }
            } else {
                Log.d("MessageReceiver", "No se encontró threadId => fallback universal.")
                val universalDecrypted = applyUniversalConvDecryptionIfEnabled(currentPlaintext)
                if (universalDecrypted != null) {
                    currentPlaintext = universalDecrypted
                    Log.d("MessageReceiver", "Descifrado universal => success sin threadId.")
                }
            }
            plaintext = currentPlaintext
        }

        val stripped = PushTransportDetails.getStrippedPaddingMessageBody(plaintext)
        val proto = SignalServiceProtos.Content.parseFrom(stripped)

        val msg: Message = ReadReceipt.fromProto(proto)
            ?: TypingIndicator.fromProto(proto)
            ?: ClosedGroupControlMessage.fromProto(proto)
            ?: DataExtractionNotification.fromProto(proto)
            ?: ExpirationTimerUpdate.fromProto(proto)
            ?: ConfigurationMessage.fromProto(proto)
            ?: UnsendRequest.fromProto(proto)
            ?: MessageRequestResponse.fromProto(proto)
            ?: CallMessage.fromProto(proto)
            ?: SharedConfigurationMessage.fromProto(proto)
            ?: VisibleMessage.fromProto(proto)
            ?: throw Error.UnknownMessage

        val isUserSender = (sender == userPublicKey)
        if (isUserSender) {
            if (!msg.isSelfSendValid) throw Error.SelfSend
            msg.isSenderSelf = true
        }

        if (isOpenGroupMessage && msg !is VisibleMessage) {
            Log.d("MessageReceiver", "Mensaje en openGroup => pero no es VisibleMessage => invalid.")
            throw Error.InvalidMessage
        }

        msg.sender = sender
        msg.recipient = userPublicKey
        msg.sentTimestamp = envelope.timestamp
        msg.receivedTimestamp =
            if (envelope.hasServerTimestamp()) envelope.serverTimestamp else SnodeAPI.nowWithOffset
        msg.groupPublicKey = groupPk
        msg.openGroupServerMessageID = openGroupServerID

        var isValid = msg.isValid()
        if (msg is VisibleMessage && !isValid && proto.dataMessage.attachmentsCount != 0) {
            isValid = true
        }
        if (!isValid) {
            throw Error.InvalidMessage
        }

        if (groupPk != null && groupPk !in (currentClosedGroups ?: emptySet())) {
            throw Error.NoGroupThread
        }

        val skipDuplicates = (
                msg is ClosedGroupControlMessage && msg.kind is ClosedGroupControlMessage.Kind.New
                ) || (msg is SharedConfigurationMessage)
        if (!skipDuplicates) {
            if (storage.isDuplicateMessage(envelope.timestamp)) {
                throw Error.DuplicateMessage
            }
            storage.addReceivedMessageTimestamp(envelope.timestamp)
        }

        if (envelope.hasThreadKeyAlias()) {
            val alias = envelope.threadKeyAlias
            Log.d("MessageReceiver", "Asignando threadID a partir de alias=$alias")
            val aliasThreadId = storage.findOrCreateThreadByAlias(alias)
            msg.threadID = aliasThreadId
        }

        return msg to proto
    }

    private fun isBlocked(sender: String): Boolean {
        return false
    }

    // ------------------------------------------------------------------
    //   Descifrado “por hilo”
    // ------------------------------------------------------------------
    private fun applyThreadDecryptionIfEnabled(cipherData: ByteArray, threadID: Long): ByteArray? {
        val storage = MessagingModuleConfiguration.shared.storage
        return try {
            val alias = storage.getThreadKeyAlias(threadID)
            if (!alias.isNullOrEmpty()) {
                val keyItem = KeysRepository.loadKeys(MessagingModuleConfiguration.shared.context)
                    .find { it.nickname == alias }
                if (keyItem != null) {
                    val rawKeyBytes = android.util.Base64.decode(keyItem.secret, android.util.Base64.NO_WRAP)
                    val decryptedBytes = RawKeyEncryptDecryptHelper.decryptBytesAESWithRawKey(cipherData, rawKeyBytes)
                    Log.d("MessageReceiver", "applyThreadDecryptionIfEnabled => used threadKeyAlias=$alias, size=${decryptedBytes.size}")
                    decryptedBytes
                } else null
            } else null
        } catch (ex: Exception) {
            Log.w("MessageReceiver", "applyThreadDecryptionIfEnabled => fail: ${ex.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    //   Descifrado universal
    // ------------------------------------------------------------------
    private fun applyUniversalConvDecryptionIfEnabled(cipherData: ByteArray): ByteArray? {
        return try {
            val ctx: Context = MessagingModuleConfiguration.shared.context
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val sharedPrefs = EncryptedSharedPreferences.create(
                ctx,
                "encryption_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val enabled = sharedPrefs.getBoolean("encryption_enabled_universalConv", false)
            val alias = sharedPrefs.getString("encryption_key_alias_universalConv", "") ?: ""
            if (enabled && alias.isNotEmpty()) {
                val keyItem = KeysRepository.loadKeys(ctx).find { it.nickname == alias }
                if (keyItem != null) {
                    val rawKeyBytes = android.util.Base64.decode(keyItem.secret, android.util.Base64.NO_WRAP)
                    val decryptedBytes = RawKeyEncryptDecryptHelper.decryptBytesAESWithRawKey(cipherData, rawKeyBytes)
                    Log.d("MessageReceiver", "applyUniversalConvDecryptionIfEnabled => success universal, size=${decryptedBytes.size}")
                    decryptedBytes
                } else null
            } else null
        } catch (ex: Exception) {
            Log.w("MessageReceiver", "applyUniversalConvDecryptionIfEnabled => error: ${ex.message}")
            null
        }
    }
}
