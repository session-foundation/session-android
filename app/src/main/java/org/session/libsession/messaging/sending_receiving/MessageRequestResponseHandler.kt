package org.session.libsession.messaging.sending_receiving

import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.ProfileUpdateHandler
import org.session.libsession.messaging.messages.ProfileUpdateHandler.Updates.Companion.toUpdates
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.updateContact
import org.session.libsession.utilities.upsertContact
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import javax.inject.Inject
import javax.inject.Provider


class MessageRequestResponseHandler @Inject constructor(
    private val recipientRepository: RecipientRepository,
    private val profileUpdateHandler: Provider<ProfileUpdateHandler>,
    private val configFactory: ConfigFactoryProtocol,
    private val mmsDatabase: MmsDatabase,
    private val smsDatabase: SmsDatabase,
    private val threadDatabase: ThreadDatabase,
    private val blindMappingRepository: BlindMappingRepository,
) {

    suspend fun handleVisibleMessage(message: VisibleMessage) {
        val (sender, receiver) = fetchSenderAndReceiver(message) ?: return

        val allBlindedAddresses = blindMappingRepository.calculateReverseMappings(
            contactAddress = sender.address as Address.Standard
        )

        // Do we have an existing message request (including blinded requests)?
        val hasMessageRequest = configFactory.withUserConfigs { configs ->
            val existingContact = configs.contacts.get(sender.address.accountId.hexString)
            if (existingContact != null && existingContact.approved && !existingContact.approvedMe) {
                return@withUserConfigs true
            }

            allBlindedAddresses.any { (_, blindedId) ->
                configs.contacts.getBlinded(blindedId.blindedId.hexString) != null
            }
        }

        if (hasMessageRequest) {
            handleRequestResponse(
                messageSender = sender,
                messageReceiver = receiver,
                messageTimestampMs = message.sentTimestamp!!,
            )
        }
    }

    suspend fun handleExplicitRequestResponseMessage(message: MessageRequestResponse) {
        val (sender, receiver) = fetchSenderAndReceiver(message) ?: return
        // Always handle explicit request response
        handleRequestResponse(
            messageSender = sender,
            messageReceiver = receiver,
            messageTimestampMs = message.sentTimestamp!!,
        )

        // Always process the profile update if any. We don't need
        // to process profile for other kind of messages as they should be handled elsewhere
        message.profile?.toUpdates()?.let { updates ->
            profileUpdateHandler.get().handleProfileUpdate(
                senderId = (sender.address as Address.Standard).accountId,
                updates = updates,
                fromCommunity = null
            )
        }
    }

    private suspend fun fetchSenderAndReceiver(message: Message): Pair<Recipient, Recipient>? {
        val messageSender = recipientRepository.getRecipient(
            requireNotNull(message.sender) {
                "MessageRequestResponse must have a sender"
            }.toAddress()
        )

        return if (messageSender.address !is Address.Standard) {
            Log.e(TAG, "MessageRequestResponse sender must be a standard address, but got: ${messageSender.address.debugString}")
            null
        } else {
            messageSender to recipientRepository.getRecipient(
                requireNotNull(message.recipient) {
                    "MessageRequestResponse must have a receiver"
                }.toAddress()
            )
        }
    }

    private fun handleRequestResponse(
        messageSender: Recipient,
        messageReceiver: Recipient,
        messageTimestampMs: Long,
    ) {
        check(messageSender.address is Address.Standard) {
            "The sender address must be a standard address"
        }

        Log.d(TAG, "Handling MessageRequestResponse from " +
                "${messageSender.address.debugString} to ${messageReceiver.address.debugString}")

        when {
            messageSender.isSelf && !messageReceiver.isSelf -> {
                // This "response" is sent by us to another user,
                // there's nothing to be done here.
            }

            !messageSender.isSelf && messageReceiver.isSelf -> {
                // We received a request response from another user.

                // Mark the sender as "approvedMe".
                // This process MUST be done before trying to update the profile,
                // as profile updating requires the contact to exist
                val didApproveMe = configFactory.withMutableUserConfigs { configs ->
                    configs.contacts.upsertContact(messageSender.address) {
                        val oldApproveMe = approvedMe
                        approvedMe = true
                        oldApproveMe
                    }
                }

                val threadId by lazy {
                    threadDatabase.getOrCreateThreadIdFor(messageSender.address)
                }

                // If it's the first time (best effort only) the sender approves us,
                // show a control message saying that they approved us.
                if (!didApproveMe) {
                    mmsDatabase.insertSecureDecryptedMessageInbox(
                        retrieved = IncomingMediaMessage(
                            messageSender.address,
                            messageTimestampMs,
                            -1,
                            0L,
                            0L,
                            true,
                            false,
                            Optional.absent(),
                            Optional.absent(),
                            Optional.absent(),
                            null,
                            Optional.absent(),
                            Optional.absent(),
                            Optional.absent(),
                            Optional.absent()
                        ),
                        threadId,
                        runThreadUpdate = true,
                    )
                }

                // Find all blinded conversations we have with this sender, move all the messages
                // from the blinded conversations to the standard conversation.
                val blindedConversationAddresses = blindMappingRepository.calculateReverseMappings(messageSender.address)
                    .mapTo(hashSetOf()) { (c, id) ->
                        Address.CommunityBlindedId(
                            serverUrl = c.baseUrl,
                            blindedId = id,
                        )
                    }

                val existingBlindedThreadIDs = threadDatabase.getThreadIDsFor(blindedConversationAddresses)
                existingBlindedThreadIDs
                    .forEach { blindedThreadId ->
                        moveConversation(fromThreadId = blindedThreadId, toThreadId = threadId)
                    }

                configFactory.withMutableUserConfigs { configs ->
                    // If we ever have any blinded conversations with this sender, we should make
                    // sure we have set "approved" to true for them, because when we started the blinded
                    // conversation, we didn't know their real standard addresses, so we didn't say
                    // we have approved them, but now that we do, we need to approve them.
                    if (existingBlindedThreadIDs.isNotEmpty()) {
                        configs.contacts.updateContact(messageSender.address) {
                            approved = true
                        }
                    }

                    // Also remove all blinded contacts
                    for (address in blindedConversationAddresses) {
                        configs.contacts.eraseBlinded(
                            communityServerUrl = address.serverUrl,
                            blindedId = address.blindedId.address
                        )
                    }
                }
            }

            else -> {

            }
        }
    }

    private fun moveConversation(fromThreadId: Long, toThreadId: Long) {
        check(fromThreadId != toThreadId) {
            "Cannot move conversation to the same thread"
        }

        check(fromThreadId != -1L && toThreadId != -1L) {
            "Cannot move conversation to or from a non-existent thread"
        }

        Log.d(TAG, "Moving conversation from thread $fromThreadId to $toThreadId")

        mmsDatabase.updateThreadId(fromThreadId, toThreadId)
        smsDatabase.updateThreadId(fromThreadId, toThreadId)
        threadDatabase.deleteThread(fromThreadId)
    }

    companion object {
        private const val TAG = "MessageRequestResponseHandler"
    }
}