package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.messages.UnsupportedMessage
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.UnsupportedMessageDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Replays retained unsupported messages through the normal receive path the first time a new app
 * version runs, so a type an update has learned replaces its placeholder at the same position (the
 * replay has the same sent timestamp).
 *
 * Deliberately not a persisted job: jobs are serialised with Kryo, which can't read back anything
 * holding a protobuf, and the work only needs a logged-in user, not durability.
 *
 * Read state carries across without copying anything, because it's decided per conversation by the
 * last-seen timestamp rather than per row. For the same reason the replay doesn't notify again for a
 * placeholder that was already seen.
 */
class UnsupportedMessageReprocessor @Inject constructor(
    private val unsupportedMessageDatabase: UnsupportedMessageDatabase,
    private val mmsDatabase: MmsDatabase,
    private val messageParser: MessageParser,
    private val receivedMessageProcessor: ReceivedMessageProcessor,
    private val snodeClock: SnodeClock,
) : AuthAwareComponent {

    private enum class Result { REPLACED, STILL_UNSUPPORTED, DROPPED }

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        withContext(Dispatchers.IO) {
            unsupportedMessageDatabase.enforceLimits(snodeClock.currentTimeMillis())

            val version = UnsupportedMessageHandler.currentVersion()
            val ids = unsupportedMessageDatabase.idsNotAttemptedBy(version)

            if (ids.isEmpty()) return@withContext

            val results = receivedMessageProcessor.startProcessing("UnsupportedMessageReprocessor") { ctx ->
                ids.map { id -> reprocess(ctx, id, version) }
            }

            Log.i(
                TAG,
                "Reprocessed ${ids.size} retained message(s): " +
                        "${results.count { it == Result.REPLACED }} replaced, " +
                        "${results.count { it == Result.DROPPED }} dropped"
            )
        }
    }

    private fun reprocess(
        ctx: ReceivedMessageProcessor.MessageProcessingContext,
        id: Long,
        version: String,
    ): Result {
        val record = unsupportedMessageDatabase.get(id) ?: return Result.DROPPED
        val isGroup = record.namespace == Namespace.GROUP_MESSAGES()

        val result = try {
            if (isGroup) {
                messageParser.parseGroupMessage(
                    data = record.data,
                    serverHash = record.hash,
                    groupId = AccountId(record.swarmPublicKey),
                    currentUserEd25519PrivKey = ctx.currentUserEd25519KeyPair.secretKey.data,
                    currentUserId = ctx.currentUserId,
                    serverTimestampMs = record.serverTimestampMs,
                    serverExpiryMs = record.serverExpiryMs,
                )
            } else {
                messageParser.parse1o1Message(
                    data = record.data,
                    serverHash = record.hash,
                    currentUserEd25519PrivKey = ctx.currentUserEd25519KeyPair.secretKey.data,
                    currentUserId = ctx.currentUserId,
                    serverTimestampMs = record.serverTimestampMs,
                    serverExpiryMs = record.serverExpiryMs,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            // This version can't process it and never will (eg. the group keys are gone), so stop
            // retaining the bytes but leave any placeholder as a permanent "can't be displayed".
            Log.w(TAG, "Dropping retained message ${record.hash}: ${e.javaClass.simpleName}")
            unsupportedMessageDatabase.delete(id)
            return Result.DROPPED
        }

        if (result.message is UnsupportedMessage) {
            unsupportedMessageDatabase.setLastAttemptVersion(id, version)
            return Result.STILL_UNSUPPORTED
        }

        // The placeholder has the same sent timestamp as the replay, so it has to go first or the
        // replay is discarded as a duplicate of it. Deleting it also removes the record.
        record.placeholderMessageId?.let(mmsDatabase::deleteMessage)
        unsupportedMessageDatabase.delete(id)

        val threadAddress = if (isGroup) {
            Address.Group(AccountId(record.swarmPublicKey))
        } else {
            result.message.senderOrSync.toAddress() as Address.Conversable
        }

        try {
            receivedMessageProcessor.processSwarmMessage(
                context = ctx,
                threadAddress = threadAddress,
                message = result.message,
                proto = result.proto,
                pro = result.pro,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.w(TAG, "Failed to process replayed message ${record.hash}: ${e.javaClass.simpleName}")
            return Result.DROPPED
        }

        return Result.REPLACED
    }

    companion object {
        private const val TAG = "UnsupportedMessageReprocessor"
    }
}
