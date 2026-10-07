package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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
import org.thoughtcrime.securesms.util.AppVisibilityManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Replays retained unsupported messages through the normal receive path the first time a new app
 * version runs, so a type an update has learned replaces its placeholder at the same position (the
 * replay has the same sent timestamp). Runs at launch and whenever the app comes to the foreground,
 * which is also when the retention limits are enforced; after the first run for a version there is
 * nothing left to attempt, so a foreground run only enforces the limits.
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
    private val appVisibilityManager: AppVisibilityManager,
) : AuthAwareComponent {

    private enum class Result {
        REPLACED,
        STILL_UNSUPPORTED,

        /** This version failed to process it for another reason; the record is kept for a later version */
        FAILED,

        /** The record went before it could be reprocessed */
        MISSING,
    }

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        run()

        // `isAppVisible` replays its current value, which the run above already covered
        appVisibilityManager.isAppVisible
            .drop(1)
            .filter { it }
            .collect { run() }
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        // Neither failure may stop the other, nor end the foreground collection
        try {
            unsupportedMessageDatabase.enforceLimits(snodeClock.currentTimeMillis())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to enforce retained message limits", e)
        }

        try {
            reprocessPending()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to reprocess retained messages", e)
        }
    }

    private fun reprocessPending() {
        val version = UnsupportedMessageHandler.currentVersion()
        val ids = unsupportedMessageDatabase.idsNotAttemptedBy(version)

        if (ids.isEmpty()) return

        val results = receivedMessageProcessor.startProcessing("UnsupportedMessageReprocessor") { ctx ->
            ids.map { id -> reprocess(ctx, id, version) }
        }

        Log.i(
            TAG,
            "Reprocessed ${ids.size} retained message(s): " +
                    "${results.count { it == Result.REPLACED }} replaced, " +
                    "${results.count { it == Result.FAILED }} failed"
        )
    }

    private fun reprocess(
        ctx: ReceivedMessageProcessor.MessageProcessingContext,
        id: Long,
        version: String,
    ): Result {
        val record = unsupportedMessageDatabase.get(id) ?: return Result.MISSING
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
            return markFailed(record, version, e)
        }

        if (result.message is UnsupportedMessage) {
            unsupportedMessageDatabase.setLastAttemptVersion(id, version)
            return Result.STILL_UNSUPPORTED
        }

        val threadAddress = if (isGroup) {
            Address.Group(AccountId(record.swarmPublicKey))
        } else {
            result.message.senderOrSync.toAddress() as Address.Conversable
        }

        try {
            // The placeholder has the same sent timestamp as the replay, so it has to go first or
            // the replay is discarded as a duplicate of it. It's detached first so the mms trigger
            // leaves the record in place in case the replay fails.
            record.placeholderMessageId?.let { placeholderId ->
                unsupportedMessageDatabase.detachPlaceholder(
                    id = id,
                    expiresAtMs = record.expiresAtMs ?: UnsupportedMessageHandler.retainedExpiryMs(
                        message = result.message,
                        serverTimestampMs = record.serverTimestampMs,
                        serverExpiryMs = record.serverExpiryMs,
                    ),
                )
                mmsDatabase.deleteMessage(placeholderId)
            }

            receivedMessageProcessor.processSwarmMessage(
                context = ctx,
                threadAddress = threadAddress,
                message = result.message,
                proto = result.proto,
                pro = result.pro,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return markFailed(record, version, e)
        }

        unsupportedMessageDatabase.delete(id)
        return Result.REPLACED
    }

    /**
     * A failure might be specific to this version, or unrelated to the message (eg. the sender is
     * now blocked), so the record is kept for a later version to retry; the retention limits still
     * bound it.
     */
    private fun markFailed(record: UnsupportedMessageDatabase.Record, version: String, e: Exception): Result {
        Log.w(TAG, "Failed to reprocess retained message ${record.hash}: ${e.javaClass.simpleName}")
        unsupportedMessageDatabase.setLastAttemptVersion(record.id, version)
        return Result.FAILED
    }

    companion object {
        private const val TAG = "UnsupportedMessageReprocessor"
    }
}
