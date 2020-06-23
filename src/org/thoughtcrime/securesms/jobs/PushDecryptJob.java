package org.thoughtcrime.securesms.jobs;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.text.TextUtils;
import android.util.Pair;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.signal.libsignal.metadata.InvalidMetadataMessageException;
import org.signal.libsignal.metadata.InvalidMetadataVersionException;
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidVersionException;
import org.signal.libsignal.metadata.ProtocolLegacyMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SelfSendException;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.SecurityEvent;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.crypto.storage.SignalProtocolStoreImpl;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PushDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.StickerRecord;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.groups.GroupMessageProcessor;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.linkpreview.Link;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.loki.activities.HomeActivity;
import org.thoughtcrime.securesms.loki.database.LokiMessageDatabase;
import org.thoughtcrime.securesms.loki.database.LokiThreadDatabase;
import org.thoughtcrime.securesms.loki.protocol.ClosedGroupsProtocol;
import org.thoughtcrime.securesms.loki.protocol.EphemeralMessage;
import org.thoughtcrime.securesms.loki.protocol.FriendRequestProtocol;
import org.thoughtcrime.securesms.loki.protocol.LokiSessionResetImplementation;
import org.thoughtcrime.securesms.loki.protocol.MultiDeviceProtocol;
import org.thoughtcrime.securesms.loki.protocol.PushEphemeralMessageSendJob;
import org.thoughtcrime.securesms.loki.protocol.SessionManagementProtocol;
import org.thoughtcrime.securesms.loki.protocol.SessionMetaProtocol;
import org.thoughtcrime.securesms.loki.protocol.SyncMessagesProtocol;
import org.thoughtcrime.securesms.loki.utilities.MentionManagerUtilities;
import org.thoughtcrime.securesms.loki.utilities.PromiseUtilities;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingExpirationUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.mms.StickerSlide;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingEndSessionMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingEncryptedMessage;
import org.thoughtcrime.securesms.sms.OutgoingEndSessionMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.stickers.StickerLocator;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.loki.LokiSessionResetProtocol;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Preview;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.loki.api.fileserver.LokiFileServerAPI;
import org.whispersystems.signalservice.loki.crypto.LokiServiceCipher;
import org.whispersystems.signalservice.loki.protocol.mentions.MentionsManager;
import org.whispersystems.signalservice.loki.protocol.todo.LokiThreadFriendRequestStatus;
import org.whispersystems.signalservice.loki.utilities.PublicKeyValidation;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import network.loki.messenger.R;

import static org.thoughtcrime.securesms.loki.utilities.RecipientUtilitiesKt.recipient;

public class PushDecryptJob extends BaseJob implements InjectableType {

  public static final String KEY = "PushDecryptJob";

  public static final String TAG = PushDecryptJob.class.getSimpleName();

  private static final String KEY_MESSAGE_ID     = "message_id";
  private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

  private long messageId;
  private long smsMessageId;

  @Inject SignalServiceMessageSender messageSender;
  private Address author;

  public PushDecryptJob(Context context) {
    this(context, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId) {
    this(context, pushMessageId, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId, long smsMessageId) {
    this(new Job.Parameters.Builder()
                           .setQueue("__PUSH_DECRYPT_JOB__")
                           .setMaxAttempts(10)
                           .build(),
         pushMessageId,
         smsMessageId);
    setContext(context);
  }

  private PushDecryptJob(@NonNull Job.Parameters parameters, long pushMessageId, long smsMessageId) {
    super(parameters);

    this.messageId    = pushMessageId;
    this.smsMessageId = smsMessageId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
                             .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws NoSuchMessageException {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping, waiting for migration...");
        postMigrationNotification();
        return;
      }

      PushDatabase          database             = DatabaseFactory.getPushDatabase(context);
      SignalServiceEnvelope envelope             = database.get(messageId);
      Optional<Long>        optionalSmsMessageId = smsMessageId > 0 ? Optional.of(smsMessageId) : Optional.absent();

      handleMessage(envelope, optionalSmsMessageId, false);
      database.delete(messageId);
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {

  }

  public void processMessage(@NonNull SignalServiceEnvelope envelope, boolean isPushNotification) {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping and storing envelope, waiting for migration...");
        DatabaseFactory.getPushDatabase(context).insert(envelope);
        postMigrationNotification();
        return;
      }

      handleMessage(envelope, Optional.absent(), isPushNotification);
    }
  }

  private boolean needsMigration() {
    return !IdentityKeyUtil.hasIdentityKey(context) || TextSecurePreferences.getNeedsSqlCipherMigration(context);
  }

  private void postMigrationNotification() {
    NotificationManagerCompat.from(context).notify(494949,
                                                   new NotificationCompat.Builder(context, NotificationChannels.getMessagesChannel(context))
                                                                         .setSmallIcon(R.drawable.ic_notification)
                                                                         .setPriority(NotificationCompat.PRIORITY_HIGH)
                                                                         .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                                                         .setContentTitle(context.getString(R.string.PushDecryptJob_new_locked_message))
                                                                         .setContentText(context.getString(R.string.PushDecryptJob_unlock_to_view_pending_messages))
                                                                         .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, HomeActivity.class), 0))
                                                                         .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                                                                         .build());

  }

  private void handleMessage(@NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId, boolean isPushNotification) {
    try {
      GroupDatabase            groupDatabase            = DatabaseFactory.getGroupDatabase(context);
      SignalProtocolStore      axolotlStore             = new SignalProtocolStoreImpl(context);
      LokiSessionResetProtocol lokiSessionResetProtocol = new LokiSessionResetImplementation(context);
      SignalServiceAddress     localAddress             = new SignalServiceAddress(TextSecurePreferences.getLocalNumber(context));
      LokiServiceCipher        cipher                   = new LokiServiceCipher(localAddress, axolotlStore, lokiSessionResetProtocol, UnidentifiedAccessUtil.getCertificateValidator());

      SignalServiceContent content = cipher.decrypt(envelope);

      // Loki - Ignore any friend requests from before restoration
      if (FriendRequestProtocol.isFriendRequestFromBeforeRestoration(context, content)) {
        Log.d("Loki", "Ignoring friend request from before restoration.");
        return;
      }

      if (shouldIgnore(content)) {
        Log.i(TAG, "Ignoring message.");
        return;
      }

      // Loki - Handle pre key bundle message if needed
      SessionManagementProtocol.handlePreKeyBundleMessageIfNeeded(context, content);

      // Loki - Handle session request if needed
      if (SessionManagementProtocol.handleSessionRequestIfNeeded(context, content)) { return; } // Don't process the message any further

      // Loki - Handle profile update if needed
      SessionMetaProtocol.handleProfileUpdateIfNeeded(context, content);

      if (content.getDeviceLink().isPresent()) {
        MultiDeviceProtocol.handleDeviceLinkMessageIfNeeded(context, content.getDeviceLink().get(), content);
      } else if (content.getDataMessage().isPresent()) {
        SignalServiceDataMessage message        = content.getDataMessage().get();
        boolean                  isMediaMessage = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent() || message.getPreviews().isPresent() || message.getSticker().isPresent();

        // Loki - Handle unlinking request if needed
        if (message.isUnlinkingRequest()) {
          MultiDeviceProtocol.handleUnlinkingRequestIfNeeded(context, content);
        } else {
          // Loki - Don't process session restoration requests any further
          if (message.isSessionRestorationRequest()) { return; }

          // Loki - Handle friend request acceptance if needed
          FriendRequestProtocol.handleFriendRequestAcceptanceIfNeeded(context, content.getSender(), content);

          if (message.isEndSession()) {
            handleEndSessionMessage(content, smsMessageId);
          } else if (message.isGroupUpdate()) {
            handleGroupMessage(content, message, smsMessageId);
          } else if (message.isExpirationUpdate()) {
            handleExpirationUpdate(content, message, smsMessageId);
          } else if (isMediaMessage) {
            handleMediaMessage(content, message, smsMessageId, Optional.absent());

            // Loki - This is needed for compatibility with refactored desktop clients
            if (!message.isGroupMessage()) {
              Recipient recipient = recipient(context, content.getSender());
              long threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
              LokiThreadDatabase threadDB = DatabaseFactory.getLokiThreadDatabase(context);
              LokiThreadFriendRequestStatus threadFriendRequestStatus = threadDB.getFriendRequestStatus(threadID);
              if (threadFriendRequestStatus == LokiThreadFriendRequestStatus.NONE || threadFriendRequestStatus == LokiThreadFriendRequestStatus.REQUEST_EXPIRED) {
                threadDB.setFriendRequestStatus(threadID, LokiThreadFriendRequestStatus.REQUEST_RECEIVED);
              } else if (threadFriendRequestStatus == LokiThreadFriendRequestStatus.REQUEST_SENT) {
                threadDB.setFriendRequestStatus(threadID, LokiThreadFriendRequestStatus.FRIENDS);
                EphemeralMessage ephemeralMessage = EphemeralMessage.create(content.getSender());
                ApplicationContext.getInstance(context).getJobManager().add(new PushEphemeralMessageSendJob(ephemeralMessage));
                SyncMessagesProtocol.syncContact(context, Address.fromSerialized(content.getSender()));
              }

              // Loki - Handle friend request message if needed
              FriendRequestProtocol.handleFriendRequestMessageIfNeeded(context, content.getSender(), content);
            }
          } else if (message.getBody().isPresent()) {
            handleTextMessage(content, message, smsMessageId, Optional.absent());

            // Loki - This is needed for compatibility with refactored desktop clients
            if (!message.isGroupMessage()) {
              Recipient recipient = recipient(context, content.getSender());
              long threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
              LokiThreadDatabase threadDB = DatabaseFactory.getLokiThreadDatabase(context);
              LokiThreadFriendRequestStatus threadFriendRequestStatus = threadDB.getFriendRequestStatus(threadID);
              if (threadFriendRequestStatus == LokiThreadFriendRequestStatus.NONE || threadFriendRequestStatus == LokiThreadFriendRequestStatus.REQUEST_EXPIRED) {
                threadDB.setFriendRequestStatus(threadID, LokiThreadFriendRequestStatus.REQUEST_RECEIVED);
              } else if (threadFriendRequestStatus == LokiThreadFriendRequestStatus.REQUEST_SENT) {
                threadDB.setFriendRequestStatus(threadID, LokiThreadFriendRequestStatus.FRIENDS);
                EphemeralMessage ephemeralMessage = EphemeralMessage.create(content.getSender());
                ApplicationContext.getInstance(context).getJobManager().add(new PushEphemeralMessageSendJob(ephemeralMessage));
                SyncMessagesProtocol.syncContact(context, Address.fromSerialized(content.getSender()));
              }

              // Loki - Handle friend request message if needed
              FriendRequestProtocol.handleFriendRequestMessageIfNeeded(context, content.getSender(), content);
            }
          } else {
            // Loki - This is needed for compatibility with refactored desktop clients
            if (envelope.isFriendRequest()) {
              EphemeralMessage ephemeralMessage = EphemeralMessage.create(content.getSender());
              ApplicationContext.getInstance(context).getJobManager().add(new PushEphemeralMessageSendJob(ephemeralMessage));
            }
          }

          if (message.getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getGroupInfo().get()))) {
            handleUnknownGroupMessage(content, message.getGroupInfo().get());
          }

          if (message.getProfileKey().isPresent() && message.getProfileKey().get().length == 32) {
            handleProfileKey(content, message);
          }

          if (content.isNeedsReceipt()) {
            handleNeedsDeliveryReceipt(content, message);
          }
        }
      } else if (content.getSyncMessage().isPresent()) {
        TextSecurePreferences.setMultiDevice(context, true);

        SignalServiceSyncMessage syncMessage = content.getSyncMessage().get();

        if      (syncMessage.getSent().isPresent())                  handleSynchronizeSentMessage(content, syncMessage.getSent().get());
        else if (syncMessage.getRequest().isPresent())               handleSynchronizeRequestMessage(syncMessage.getRequest().get());
        else if (syncMessage.getRead().isPresent())                  handleSynchronizeReadMessage(syncMessage.getRead().get(), content.getTimestamp());
        else if (syncMessage.getVerified().isPresent())              handleSynchronizeVerifiedMessage(syncMessage.getVerified().get());
        else if (syncMessage.getStickerPackOperations().isPresent()) handleSynchronizeStickerPackOperation(syncMessage.getStickerPackOperations().get());
        else if (syncMessage.getContacts().isPresent())              SyncMessagesProtocol.handleContactSyncMessage(context, content, syncMessage.getContacts().get());
        else if (syncMessage.getGroups().isPresent())                SyncMessagesProtocol.handleClosedGroupSyncMessage(context, content, syncMessage.getGroups().get());
        else if (syncMessage.getOpenGroups().isPresent())            SyncMessagesProtocol.handleOpenGroupSyncMessage(context, content, syncMessage.getOpenGroups().get());
        else                                                         Log.w(TAG, "Contains no known sync types...");
      } else if (content.getCallMessage().isPresent()) {
        Log.i(TAG, "Got call message...");
        SignalServiceCallMessage message = content.getCallMessage().get();

        if      (message.getOfferMessage().isPresent())      handleCallOfferMessage(content, message.getOfferMessage().get(), smsMessageId);
        else if (message.getAnswerMessage().isPresent())     handleCallAnswerMessage(content, message.getAnswerMessage().get());
        else if (message.getIceUpdateMessages().isPresent()) handleCallIceUpdateMessage(content, message.getIceUpdateMessages().get());
        else if (message.getHangupMessage().isPresent())     handleCallHangupMessage(content, message.getHangupMessage().get(), smsMessageId);
        else if (message.getBusyMessage().isPresent())       handleCallBusyMessage(content, message.getBusyMessage().get());
      } else if (content.getReceiptMessage().isPresent()) {
        SignalServiceReceiptMessage message = content.getReceiptMessage().get();

        if      (message.isReadReceipt())     handleReadReceipt(content, message);
        else if (message.isDeliveryReceipt()) handleDeliveryReceipt(content, message);
      } else if (content.getTypingMessage().isPresent()) {
        handleTypingMessage(content, content.getTypingMessage().get());
      } else {
        Log.w(TAG, "Got unrecognized message...");
      }

      resetRecipientToPush(Recipient.from(context, Address.fromSerialized(content.getSender()), false));

      if (envelope.isPreKeySignalMessage()) {
        ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob());
      }
    } catch (ProtocolInvalidVersionException e) {
      Log.w(TAG, e);
      handleInvalidVersionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolInvalidMessageException e) {
      Log.w(TAG, e);
      if (!isPushNotification) { // This can be triggered if a PN encrypted with an old session comes in after the user performed a session reset
        handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
      }
    } catch (ProtocolInvalidKeyIdException | ProtocolInvalidKeyException | ProtocolUntrustedIdentityException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (StorageFailedException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolNoSessionException e) {
      Log.w(TAG, e);
      handleNoSessionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolLegacyMessageException e) {
      Log.w(TAG, e);
      handleLegacyMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolDuplicateMessageException e) {
      Log.w(TAG, e);
      handleDuplicateMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (InvalidMetadataVersionException | InvalidMetadataMessageException e) {
      Log.w(TAG, e);
    } catch (SelfSendException e) {
      Log.i(TAG, "Dropping UD message from self.");
    }
  }

  private void handleCallOfferMessage(@NonNull SignalServiceContent content,
                                      @NonNull OfferMessage message,
                                      @NonNull Optional<Long> smsMessageId)
  {
    Log.w(TAG, "handleCallOfferMessage...");

    if (smsMessageId.isPresent()) {
      SmsDatabase database = DatabaseFactory.getSmsDatabase(context);
      database.markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_INCOMING_CALL);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromSerialized(content.getSender()));
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());
      intent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, content.getTimestamp());

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
      else                                                context.startService(intent);
    }
  }

  private void handleCallAnswerMessage(@NonNull SignalServiceContent content,
                                       @NonNull AnswerMessage message)
  {
    Log.i(TAG, "handleCallAnswerMessage...");
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_RESPONSE_MESSAGE);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromSerialized(content.getSender()));
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());

    context.startService(intent);
  }

  private void handleCallIceUpdateMessage(@NonNull SignalServiceContent content,
                                          @NonNull List<IceUpdateMessage> messages)
  {
    Log.w(TAG, "handleCallIceUpdateMessage... " + messages.size());
    for (IceUpdateMessage message : messages) {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_ICE_MESSAGE);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromSerialized(content.getSender()));
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP, message.getSdp());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_MID, message.getSdpMid());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_LINE_INDEX, message.getSdpMLineIndex());

      context.startService(intent);
    }
  }

  private void handleCallHangupMessage(@NonNull SignalServiceContent content,
                                       @NonNull HangupMessage message,
                                       @NonNull Optional<Long> smsMessageId)
  {
    Log.i(TAG, "handleCallHangupMessage");
    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_REMOTE_HANGUP);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromSerialized(content.getSender()));

      context.startService(intent);
    }
  }

  private void handleCallBusyMessage(@NonNull SignalServiceContent content,
                                     @NonNull BusyMessage message)
  {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_REMOTE_BUSY);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromSerialized(content.getSender()));

    context.startService(intent);
  }

  private void handleEndSessionMessage(@NonNull SignalServiceContent content,
                                       @NonNull Optional<Long>       smsMessageId)
  {
    SmsDatabase         smsDatabase         = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(Address.fromSerialized(content.getSender()),
                                                                      content.getSenderDevice(),
                                                                      content.getTimestamp(),
                                                                      "", Optional.absent(), 0,
                                                                      content.isNeedsReceipt());

    Long threadId;

    if (!smsMessageId.isPresent()) {
      IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
      Optional<InsertResult>    insertResult              = smsDatabase.insertMessageInbox(incomingEndSessionMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;
    } else {
      smsDatabase.markAsEndSession(smsMessageId.get());
      threadId = smsDatabase.getThreadIdForMessage(smsMessageId.get());
    }

    if (threadId != null) {
      SessionManagementProtocol.handleEndSessionMessageIfNeeded(context, content);
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentEndSessionMessage(@NonNull SentTranscriptMessage message)
  {
    SmsDatabase               database                  = DatabaseFactory.getSmsDatabase(context);
    Recipient                 recipient                 = getSyncMessageDestination(message);
    OutgoingTextMessage       outgoingTextMessage       = new OutgoingTextMessage(recipient, "", -1);
    OutgoingEndSessionMessage outgoingEndSessionMessage = new OutgoingEndSessionMessage(outgoingTextMessage);

    long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);

    if (!recipient.isGroupRecipient()) {
      // TODO: Handle session reset on sync messages
      /*
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(recipient.getAddress().toPhoneString());
       */

      SecurityEvent.broadcastSecurityUpdateEvent(context);

      long messageId = database.insertMessageOutbox(threadId, outgoingEndSessionMessage,
                                                    false, message.getTimestamp(),
                                                    null);
      database.markAsSent(messageId, true);
    }

    return threadId;
  }

  private void handleGroupMessage(@NonNull SignalServiceContent content,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    GroupMessageProcessor.process(context, content, message, false);

    if (message.getExpiresInSeconds() != 0 && message.getExpiresInSeconds() != getMessageDestination(content, message).getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
    }
  }

  private void handleUnknownGroupMessage(@NonNull SignalServiceContent content,
                                         @NonNull SignalServiceGroup group)
  {
    if (group.getGroupType() == SignalServiceGroup.GroupType.SIGNAL) {
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new RequestGroupInfoJob(content.getSender(), group.getGroupId()));
    }
  }

  private void handleExpirationUpdate(@NonNull SignalServiceContent content,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    try {
      MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
      Recipient            recipient    = getMessageMasterDestination(content.getSender());
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(getMessageMasterDestination(content.getSender()).getAddress(),
                                                                   message.getTimestamp(), -1,
                                                                   message.getExpiresInSeconds() * 1000L, true,
                                                                   content.isNeedsReceipt(),
                                                                   Optional.absent(),
                                                                   message.getGroupInfo(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent());

        database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

        DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getExpiresInSeconds());

      if (smsMessageId.isPresent()) {
        DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeVerifiedMessage(@NonNull VerifiedMessage verifiedMessage) {
    IdentityUtil.processVerifiedMessage(context, verifiedMessage);
  }

  private void handleSynchronizeStickerPackOperation(@NonNull List<StickerPackOperationMessage> stickerPackOperations) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();

    for (StickerPackOperationMessage operation : stickerPackOperations) {
      if (operation.getPackId().isPresent() && operation.getPackKey().isPresent() && operation.getType().isPresent()) {
        String packId  = Hex.toStringCondensed(operation.getPackId().get());
        String packKey = Hex.toStringCondensed(operation.getPackKey().get());

        switch (operation.getType().get()) {
          case INSTALL:
            jobManager.add(new StickerPackDownloadJob(packId, packKey, false));
            break;
          case REMOVE:
            DatabaseFactory.getStickerDatabase(context).uninstallPack(packId);
            break;
        }
      } else {
        Log.w(TAG, "Received incomplete sticker pack operation sync.");
      }
    }
  }

  private void handleSynchronizeSentMessage(@NonNull SignalServiceContent content,
                                            @NonNull SentTranscriptMessage message)
      throws StorageFailedException

  {
    try {
      GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);

      Long threadId;

      if (message.getMessage().isEndSession()) {
        threadId = handleSynchronizeSentEndSessionMessage(message);
      } else if (message.getMessage().isGroupUpdate()) {
        threadId = GroupMessageProcessor.process(context, content, message.getMessage(), true);
      } else if (message.getMessage().isExpirationUpdate()) {
        threadId = handleSynchronizeSentExpirationUpdate(message);
      } else if (message.getMessage().getAttachments().isPresent() || message.getMessage().getQuote().isPresent() || message.getMessage().getPreviews().isPresent() || message.getMessage().getSticker().isPresent()) {
        threadId = handleSynchronizeSentMediaMessage(message);
      } else {
        threadId = handleSynchronizeSentTextMessage(message);
      }

      if (threadId == -1L) { threadId = null; }

      if (message.getMessage().getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get()))) {
        handleUnknownGroupMessage(content, message.getMessage().getGroupInfo().get());
      }

      if (message.getMessage().getProfileKey().isPresent()) {
        Recipient recipient = null;

        if      (message.getDestination().isPresent())            recipient = Recipient.from(context, Address.fromSerialized(message.getDestination().get()), false);
        else if (message.getMessage().getGroupInfo().isPresent()) recipient = Recipient.from(context, Address.fromSerialized(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get())), false);


        if (recipient != null && !recipient.isSystemContact() && !recipient.isProfileSharing()) {
          DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient, true);
        }

        // Loki - Handle profile key update if needed
        handleProfileKey(content, message.getMessage());
      }

      // Loki - Update profile if needed
      SessionMetaProtocol.handleProfileUpdateIfNeeded(context, content);

      if (threadId != null) {
        DatabaseFactory.getThreadDatabase(context).setRead(threadId, true);
        MessageNotifier.updateNotification(context);
      }

      MessageNotifier.setLastDesktopActivityTimestamp(message.getTimestamp());
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeRequestMessage(@NonNull RequestMessage message)
  {
    if (message.isContactsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceContactUpdateJob(context, true));

      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new RefreshUnidentifiedDeliveryAbilityJob());
    }

    if (message.isGroupsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceGroupUpdateJob());
    }

    if (message.isBlockedListRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceBlockedUpdateJob());
    }

    if (message.isConfigurationRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceConfigurationUpdateJob(TextSecurePreferences.isReadReceiptsEnabled(context),
                                                                   TextSecurePreferences.isTypingIndicatorsEnabled(context),
                                                                   TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context),
                                                                   TextSecurePreferences.isLinkPreviewsEnabled(context)));

      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceStickerPackSyncJob());
    }
  }

  private void handleSynchronizeReadMessage(@NonNull List<ReadMessage> readMessages, long envelopeTimestamp)
  {
    for (ReadMessage readMessage : readMessages) {
      List<Pair<Long, Long>> expiringText = DatabaseFactory.getSmsDatabase(context).setTimestampRead(new SyncMessageId(Address.fromSerialized(readMessage.getSender()), readMessage.getTimestamp()), envelopeTimestamp);
      List<Pair<Long, Long>> expiringMedia = DatabaseFactory.getMmsDatabase(context).setTimestampRead(new SyncMessageId(Address.fromSerialized(readMessage.getSender()), readMessage.getTimestamp()), envelopeTimestamp);

      for (Pair<Long, Long> expiringMessage : expiringText) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, false, envelopeTimestamp, expiringMessage.second);
      }

      for (Pair<Long, Long> expiringMessage : expiringMedia) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, true, envelopeTimestamp, expiringMessage.second);
      }
    }

    MessageNotifier.setLastDesktopActivityTimestamp(envelopeTimestamp);
    MessageNotifier.cancelDelayedNotifications();
    MessageNotifier.updateNotification(context);
  }

  public void handleMediaMessage(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId,
                                 @NonNull Optional<Long> messageServerIDOrNull)
      throws StorageFailedException
  {
    Recipient originalRecipient = getMessageDestination(content, message);
    Recipient masterRecipient = getMessageMasterDestination(content.getSender());

    notifyTypingStoppedFromIncomingMessage(masterRecipient, content.getSender(), content.getSenderDevice());

    Optional<QuoteModel>        quote          = getValidatedQuote(message.getQuote());
    Optional<List<Contact>>     sharedContacts = getContacts(message.getSharedContacts());
    Optional<List<LinkPreview>> linkPreviews   = getLinkPreviews(message.getPreviews(), message.getBody().or(""));
    Optional<Attachment>        sticker        = getStickerAttachment(message.getSticker());

    Address masterAddress = masterRecipient.getAddress();

    if (message.isGroupMessage()) {
      masterAddress = getMessageMasterDestination(content.getSender()).getAddress();
    }

    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(masterAddress, message.getTimestamp(), -1,
      message.getExpiresInSeconds() * 1000L, false, content.isNeedsReceipt(), message.getBody(), message.getGroupInfo(), message.getAttachments(),
      quote, sharedContacts, linkPreviews, sticker);

    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.beginTransaction();

    // Loki - Ignore message if it has no body and no attachments
    if (mediaMessage.getBody().isEmpty() && mediaMessage.getAttachments().isEmpty() && mediaMessage.getLinkPreviews().isEmpty()) {
      return;
    }

    Optional<InsertResult> insertResult;

    try {
      insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      if (insertResult.isPresent()) {
        List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
        List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
        List<DatabaseAttachment> attachments        = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

        forceStickerDownloadIfNecessary(stickerAttachments);

        for (DatabaseAttachment attachment : attachments) {
          ApplicationContext.getInstance(context).getJobManager().add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
        }

        if (smsMessageId.isPresent()) {
          DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
        }

        database.setTransactionSuccessful();
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    } finally {
      database.endTransaction();
    }

    if (insertResult.isPresent()) {
      MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
    }

    // Loki - Store message open group server ID if needed
    if (insertResult.isPresent() && messageServerIDOrNull.isPresent()) {
      long messageID = insertResult.get().getMessageId();
      long messageServerID = messageServerIDOrNull.get();
      LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
      lokiMessageDatabase.setServerID(messageID, messageServerID);
    }

    // Loki - Update mapping of message ID to original thread ID
    if (insertResult.isPresent()) {
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
      LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
      long originalThreadId = threadDatabase.getThreadIdFor(originalRecipient);
      lokiMessageDatabase.setOriginalThreadID(insertResult.get().getMessageId(), originalThreadId);
    }
  }

  private long handleSynchronizeSentExpirationUpdate(@NonNull SentTranscriptMessage message) throws MmsException {
    MmsDatabase database  = DatabaseFactory.getMmsDatabase(context);
    Recipient   recipient = getSyncMessageMasterDestination(message);

    OutgoingExpirationUpdateMessage expirationUpdateMessage = new OutgoingExpirationUpdateMessage(recipient,
                                                                                                  message.getTimestamp(),
                                                                                                  message.getMessage().getExpiresInSeconds() * 1000L);

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    long messageId = database.insertMessageOutbox(expirationUpdateMessage, threadId, false, null);

    database.markAsSent(messageId, true);

    DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getMessage().getExpiresInSeconds());

    return threadId;
  }

  public long handleSynchronizeSentMediaMessage(@NonNull SentTranscriptMessage message)
      throws MmsException
  {
    MmsDatabase                 database        = DatabaseFactory.getMmsDatabase(context);
    Recipient                   recipients      = getSyncMessageMasterDestination(message);
    Optional<QuoteModel>        quote           = getValidatedQuote(message.getMessage().getQuote());
    Optional<Attachment>        sticker         = getStickerAttachment(message.getMessage().getSticker());
    Optional<List<Contact>>     sharedContacts  = getContacts(message.getMessage().getSharedContacts());
    Optional<List<LinkPreview>> previews        = getLinkPreviews(message.getMessage().getPreviews(), message.getMessage().getBody().or(""));
    List<Attachment>            syncAttachments = PointerAttachment.forPointers(message.getMessage().getAttachments());

    if (sticker.isPresent()) {
      syncAttachments.add(sticker.get());
    }

    OutgoingMediaMessage mediaMessage = new OutgoingMediaMessage(recipients, message.getMessage().getBody().orNull(),
                                                                 syncAttachments,
                                                                 message.getTimestamp(), -1,
                                                                 message.getMessage().getExpiresInSeconds() * 1000,
                                                                 ThreadDatabase.DistributionTypes.DEFAULT, quote.orNull(),
                                                                 sharedContacts.or(Collections.emptyList()),
                                                                 previews.or(Collections.emptyList()),
                                                                 Collections.emptyList(), Collections.emptyList());

    mediaMessage = new OutgoingSecureMediaMessage(mediaMessage);

    if (recipients.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);

    database.beginTransaction();

    try {
      long messageId = database.insertMessageOutbox(mediaMessage, threadId, false, null);
      if (message.messageServerID >= 0) { DatabaseFactory.getLokiMessageDatabase(context).setServerID(messageId, message.messageServerID); }

      if (recipients.getAddress().isGroup()) {
        GroupReceiptDatabase receiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);
        List<Recipient>      members         = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipients.getAddress().toGroupString(), false);

        for (Recipient member : members) {
          receiptDatabase.setUnidentified(member.getAddress(), messageId, message.isUnidentified(member.getAddress().serialize()));
        }
      }

      database.markAsSent(messageId, true);
      database.markUnidentified(messageId, message.isUnidentified(recipients.getAddress().serialize()));

      List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageId);
      List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
      List<DatabaseAttachment> attachments        = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

      forceStickerDownloadIfNecessary(stickerAttachments);

      for (DatabaseAttachment attachment : attachments) {
        ApplicationContext.getInstance(context)
                          .getJobManager()
                          .add(new AttachmentDownloadJob(messageId, attachment.getAttachmentId(), false));
      }

      if (message.getMessage().getExpiresInSeconds() > 0) {
        database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(messageId, true,
                                            message.getExpirationStartTimestamp(),
                                            message.getMessage().getExpiresInSeconds() * 1000L);
      }

      if (recipients.isLocalNumber()) {
        SyncMessageId id = new SyncMessageId(recipients.getAddress(), message.getTimestamp());
        DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
        DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
      }

      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
    return threadId;
  }

  public void handleTextMessage(@NonNull SignalServiceContent content,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Optional<Long> smsMessageId,
                                @NonNull Optional<Long> messageServerIDOrNull)
      throws StorageFailedException
  {
    SmsDatabase database          = DatabaseFactory.getSmsDatabase(context);
    String      body              = message.getBody().isPresent() ? message.getBody().get() : "";
    Recipient   originalRecipient = getMessageDestination(content, message);
    Recipient   masterRecipient   = getMessageMasterDestination(content.getSender());

    if (message.getExpiresInSeconds() != originalRecipient.getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    Long threadId = null;

    if (smsMessageId.isPresent() && !message.getGroupInfo().isPresent()) {
      threadId = database.updateBundleMessageBody(smsMessageId.get(), body).second;
    } else {
      notifyTypingStoppedFromIncomingMessage(masterRecipient, content.getSender(), content.getSenderDevice());

      Address masterAddress = masterRecipient.getAddress();

      if (message.isGroupMessage()) {
        masterAddress = getMessageMasterDestination(content.getSender()).getAddress();
      }

      IncomingTextMessage tm = new IncomingTextMessage(masterAddress,
                                                       content.getSenderDevice(),
                                                       message.getTimestamp(), body,
                                                       message.getGroupInfo(),
                                                       message.getExpiresInSeconds() * 1000L,
                                                       content.isNeedsReceipt());

      IncomingEncryptedMessage textMessage = new IncomingEncryptedMessage(tm, body);

      // Ignore the message if it has no body
      if (textMessage.getMessageBody().length() == 0) { return; }

      // Insert the message into the database
      Optional<InsertResult> insertResult = database.insertMessageInbox(textMessage);

      if (insertResult.isPresent()) {
        threadId = insertResult.get().getThreadId();
      }

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());

      if (threadId != null) {
        MessageNotifier.updateNotification(context, threadId);
      }

      if (insertResult.isPresent()) {
        InsertResult result = insertResult.get();

        // Loki - Cache the user hex encoded public key (for mentions)
        MentionManagerUtilities.INSTANCE.populateUserPublicKeyCacheIfNeeded(result.getThreadId(), context);
        MentionsManager.shared.cache(textMessage.getSender().serialize(), result.getThreadId());

        // Loki - Store message server ID
        if (insertResult.isPresent() && messageServerIDOrNull.isPresent()) {
          long messageID = insertResult.get().getMessageId();
          long messageServerID = messageServerIDOrNull.get();
          LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
          lokiMessageDatabase.setServerID(messageID, messageServerID);
        }

        // Loki - Update mapping of message to original thread ID
        if (result.getMessageId() > -1) {
          ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
          LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
          long originalThreadId = threadDatabase.getThreadIdFor(originalRecipient);
          lokiMessageDatabase.setOriginalThreadID(result.getMessageId(), originalThreadId);
        }
      }
    }
  }

  public long handleSynchronizeSentTextMessage(@NonNull SentTranscriptMessage message)
      throws MmsException
  {

    Recipient recipient       = getSyncMessageMasterDestination(message);
    String    body            = message.getMessage().getBody().or("");
    long      expiresInMillis = message.getMessage().getExpiresInSeconds() * 1000L;

    // Ignore the message if it has no body
    if (body.isEmpty()) { return -1; }

    if (recipient.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long    threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    boolean isGroup   = recipient.getAddress().isGroup();

    MessagingDatabase database;
    long              messageId;

    if (isGroup) {
      OutgoingMediaMessage outgoingMediaMessage = new OutgoingMediaMessage(recipient, new SlideDeck(), body, message.getTimestamp(), -1, expiresInMillis, ThreadDatabase.DistributionTypes.DEFAULT, null, Collections.emptyList(), Collections.emptyList());
      outgoingMediaMessage = new OutgoingSecureMediaMessage(outgoingMediaMessage);

      messageId = DatabaseFactory.getMmsDatabase(context).insertMessageOutbox(outgoingMediaMessage, threadId, false, null);
      if (message.messageServerID >= 0) { DatabaseFactory.getLokiMessageDatabase(context).setServerID(messageId, message.messageServerID); }

      database  = DatabaseFactory.getMmsDatabase(context);

      GroupReceiptDatabase receiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);
      List<Recipient>      members         = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipient.getAddress().toGroupString(), false);

      for (Recipient member : members) {
        receiptDatabase.setUnidentified(member.getAddress(), messageId, message.isUnidentified(member.getAddress().serialize()));
      }
    } else {
      OutgoingTextMessage outgoingTextMessage = new OutgoingEncryptedMessage(recipient, body, expiresInMillis);

      messageId = DatabaseFactory.getSmsDatabase(context).insertMessageOutbox(threadId, outgoingTextMessage, false, message.getTimestamp(), null);
      database  = DatabaseFactory.getSmsDatabase(context);
      database.markUnidentified(messageId, message.isUnidentified(recipient.getAddress().serialize()));
    }

    database.markAsSent(messageId, true);

    if (expiresInMillis > 0) {
      database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
      ApplicationContext.getInstance(context)
                        .getExpiringMessageManager()
                        .scheduleDeletion(messageId, isGroup, message.getExpirationStartTimestamp(), expiresInMillis);
    }

    if (recipient.isLocalNumber()) {
      SyncMessageId id = new SyncMessageId(recipient.getAddress(), message.getTimestamp());
      DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
      DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
    }

    return threadId;
  }

  private void handleInvalidVersionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                           @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidVersionKeyExchange(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsInvalidVersionKeyExchange(smsMessageId.get());
    }
  }

  private void handleCorruptMessage(@NonNull String sender, int senderDevice, long timestamp,
                                    @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsDecryptFailed(smsMessageId.get());
    }
    SessionManagementProtocol.triggerSessionRestorationUI(context, sender);
  }

  private void handleNoSessionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsNoSession(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
    SessionManagementProtocol.triggerSessionRestorationUI(context, sender);
  }

  private void handleLegacyMessage(@NonNull String sender, int senderDevice, long timestamp,
                                   @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsLegacyVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsLegacyVersion(smsMessageId.get());
    }
  }

  @SuppressWarnings("unused")
  private void handleDuplicateMessage(@NonNull String sender, int senderDeviceId, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    // Let's start ignoring these now
//    SmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
//
//    if (smsMessageId <= 0) {
//      Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
//      smsDatabase.markAsDecryptDuplicate(messageAndThreadId.first);
//      MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
//    } else {
//      smsDatabase.markAsDecryptDuplicate(smsMessageId);
//    }
  }

  private void handleProfileKey(@NonNull SignalServiceContent content,
                                @NonNull SignalServiceDataMessage message)
  {
    if (!message.getProfileKey().isPresent()) { return; }

    /*
    If we get a profile key then we don't need to map it to the primary device.
    For now a profile key is mapped one-to-one to avoid secondary devices setting the incorrect avatar for a primary device.
     */
    RecipientDatabase database      = DatabaseFactory.getRecipientDatabase(context);
    Recipient         recipient     = Recipient.from(context, Address.fromSerialized(content.getSender()), false);

    if (recipient.getProfileKey() == null || !MessageDigest.isEqual(recipient.getProfileKey(), message.getProfileKey().get())) {
      database.setProfileKey(recipient, message.getProfileKey().get());
      database.setUnidentifiedAccessMode(recipient, RecipientDatabase.UnidentifiedAccessMode.UNKNOWN);
      String url = content.senderProfilePictureURL.or("");
      ApplicationContext.getInstance(context).getJobManager().add(new RetrieveProfileAvatarJob(recipient, url));

      SessionMetaProtocol.handleProfileKeyUpdateIfNeeded(context, content);
    }
  }

  private void handleNeedsDeliveryReceipt(@NonNull SignalServiceContent content,
                                          @NonNull SignalServiceDataMessage message)
  {
    ApplicationContext.getInstance(context)
                      .getJobManager()
                      .add(new SendDeliveryReceiptJob(Address.fromSerialized(content.getSender()), message.getTimestamp()));
  }

  @SuppressLint("DefaultLocale")
  private void handleDeliveryReceipt(@NonNull SignalServiceContent content,
                                     @NonNull SignalServiceReceiptMessage message)
  {
    // Redirect message to master device conversation
    Address masterAddress = Address.fromSerialized(content.getSender());

    if (masterAddress.isPhone()) {
      Recipient masterRecipient = getMessageMasterDestination(content.getSender());
      masterAddress = masterRecipient.getAddress();
    }

    for (long timestamp : message.getTimestamps()) {
      Log.i(TAG, String.format("Received encrypted delivery receipt: (XXXXX, %d)", timestamp));
      DatabaseFactory.getMmsSmsDatabase(context)
                     .incrementDeliveryReceiptCount(new SyncMessageId(masterAddress, timestamp), System.currentTimeMillis());
    }
  }

  @SuppressLint("DefaultLocale")
  private void handleReadReceipt(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {

      // Redirect message to master device conversation
      Address masterAddress = Address.fromSerialized(content.getSender());

      if (masterAddress.isPhone()) {
        Recipient masterRecipient = getMessageMasterDestination(content.getSender());
        masterAddress = masterRecipient.getAddress();
      }

      for (long timestamp : message.getTimestamps()) {
        Log.i(TAG, String.format("Received encrypted read receipt: (XXXXX, %d)", timestamp));

        DatabaseFactory.getMmsSmsDatabase(context)
                       .incrementReadReceiptCount(new SyncMessageId(masterAddress, timestamp), content.getTimestamp());
      }
    }
  }

  private void handleTypingMessage(@NonNull SignalServiceContent content,
                                   @NonNull SignalServiceTypingMessage typingMessage)
  {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }

    Recipient author = Recipient.from(context, Address.fromSerialized(content.getSender()), false);

    long threadId;

    if (typingMessage.getGroupId().isPresent()) {
      // Typing messages should only apply to closed groups, thus we use `getEncodedId`
      Address   groupAddress   = Address.fromSerialized(GroupUtil.getEncodedId(typingMessage.getGroupId().get(), false));
      Recipient groupRecipient = Recipient.from(context, groupAddress, false);

      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(groupRecipient);
    } else {
      // See if we need to redirect the message
      author = getMessageMasterDestination(content.getSender());
      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(author);
    }

    if (threadId <= 0) {
      Log.w(TAG, "Couldn't find a matching thread for a typing message.");
      return;
    }

    if (typingMessage.isTypingStarted()) {
      Log.d(TAG, "Typing started on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStarted(context,threadId, author, content.getSenderDevice());
    } else {
      Log.d(TAG, "Typing stopped on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, content.getSenderDevice(), false);
    }
  }

  private Optional<QuoteModel> getValidatedQuote(Optional<SignalServiceDataMessage.Quote> quote) {
    if (!quote.isPresent()) return Optional.absent();

    if (quote.get().getId() <= 0) {
      Log.w(TAG, "Received quote without an ID! Ignoring...");
      return Optional.absent();
    }

    if (quote.get().getAuthor() == null) {
      Log.w(TAG, "Received quote without an author! Ignoring...");
      return Optional.absent();
    }

    Address       author  = Address.fromSerialized(quote.get().getAuthor().getNumber());
    MessageRecord message = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(quote.get().getId(), author);

    if (message != null) {
      Log.i(TAG, "Found matching message record...");

      List<Attachment> attachments = new LinkedList<>();

      if (message.isMms()) {
        MmsMessageRecord mmsMessage = (MmsMessageRecord) message;
        attachments = mmsMessage.getSlideDeck().asAttachments();
        if (attachments.isEmpty()) {
          attachments.addAll(Stream.of(mmsMessage.getLinkPreviews())
                                   .filter(lp -> lp.getThumbnail().isPresent())
                                   .map(lp -> lp.getThumbnail().get())
                                   .toList());
        }
      }

      return Optional.of(new QuoteModel(quote.get().getId(), author, message.getBody(), false, attachments));
    }

    Log.w(TAG, "Didn't find matching message record...");
    return Optional.of(new QuoteModel(quote.get().getId(),
                                      author,
                                      quote.get().getText(),
                                      true,
                                      PointerAttachment.forPointers(quote.get().getAttachments())));
  }

  private Optional<Attachment> getStickerAttachment(Optional<SignalServiceDataMessage.Sticker> sticker) {
    if (!sticker.isPresent()) {
      return Optional.absent();
    }

    if (sticker.get().getPackId() == null || sticker.get().getPackKey() == null || sticker.get().getAttachment() == null) {
      Log.w(TAG, "Malformed sticker!");
      return Optional.absent();
    }

    String          packId          = Hex.toStringCondensed(sticker.get().getPackId());
    String          packKey         = Hex.toStringCondensed(sticker.get().getPackKey());
    int             stickerId       = sticker.get().getStickerId();
    StickerLocator  stickerLocator  = new StickerLocator(packId, packKey, stickerId);
    StickerDatabase stickerDatabase = DatabaseFactory.getStickerDatabase(context);
    StickerRecord   stickerRecord   = stickerDatabase.getSticker(stickerLocator.getPackId(), stickerLocator.getStickerId(), false);

    if (stickerRecord != null) {
      return Optional.of(new UriAttachment(stickerRecord.getUri(),
                                           stickerRecord.getUri(),
                                           MediaUtil.IMAGE_WEBP,
                                           AttachmentDatabase.TRANSFER_PROGRESS_DONE,
                                           stickerRecord.getSize(),
                                           StickerSlide.WIDTH,
                                           StickerSlide.HEIGHT,
                                           null,
                                           String.valueOf(new SecureRandom().nextLong()),
                                           false,
                                           false,
                                           null,
                                           stickerLocator));
    } else {
      return Optional.of(PointerAttachment.forPointer(Optional.of(sticker.get().getAttachment()), stickerLocator).get());
    }
  }

  private Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.absent();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  private Optional<List<LinkPreview>> getLinkPreviews(Optional<List<Preview>> previews, @NonNull String message) {
    if (!previews.isPresent()) return Optional.absent();

    List<LinkPreview> linkPreviews = new ArrayList<>(previews.get().size());

    for (Preview preview : previews.get()) {
      Optional<Attachment> thumbnail     = PointerAttachment.forPointer(preview.getImage());
      Optional<String>     url           = Optional.fromNullable(preview.getUrl());
      Optional<String>     title         = Optional.fromNullable(preview.getTitle());
      boolean              hasContent    = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent();
      boolean              presentInBody = url.isPresent() && Stream.of(LinkPreviewUtil.findWhitelistedUrls(message)).map(Link::getUrl).collect(Collectors.toSet()).contains(url.get());
      boolean              validDomain   = url.isPresent() && LinkPreviewUtil.isWhitelistedLinkUrl(url.get());

      if (hasContent && presentInBody && validDomain) {
        LinkPreview linkPreview = new LinkPreview(url.get(), title.or(""), thumbnail);
        linkPreviews.add(linkPreview);
      } else {
        Log.w(TAG, String.format("Discarding an invalid link preview. hasContent: %b presentInBody: %b validDomain: %b", hasContent, presentInBody, validDomain));
      }
    }

    return Optional.of(linkPreviews);
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp) {
    Recipient           masterRecipient = getMessageMasterDestination(sender);
    SmsDatabase         database        = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage textMessage     = new IncomingTextMessage(masterRecipient.getAddress(),
                                                                 senderDevice, timestamp, "",
                                                                 Optional.absent(), 0, false);

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }

  private Recipient getSyncMessageDestination(SentTranscriptMessage message) {
    if (message.getMessage().isGroupMessage()) {
      return Recipient.from(context, Address.fromSerialized(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get())), false);
    } else {
      return Recipient.from(context, Address.fromSerialized(message.getDestination().get()), false);
    }
  }

  private Recipient getSyncMessageMasterDestination(SentTranscriptMessage message) {
    if (message.getMessage().isGroupMessage()) {
      return Recipient.from(context, Address.fromSerialized(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get())), false);
    } else {
      String publicKey = message.getDestination().get();
      String userPublicKey = TextSecurePreferences.getLocalNumber(context);
      Set<String> allUserDevices = org.whispersystems.signalservice.loki.protocol.multidevice.MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey);
      if (allUserDevices.contains(publicKey)) {
        return Recipient.from(context, Address.fromSerialized(userPublicKey), false);
      } else {
        try {
          // TODO: Burn this with fire when we can
          PromiseUtilities.timeout(LokiFileServerAPI.shared.getDeviceLinks(publicKey, false), 6000).get();
          String masterPublicKey = org.whispersystems.signalservice.loki.protocol.multidevice.MultiDeviceProtocol.shared.getMasterDevice(publicKey);
          if (masterPublicKey == null) {
            masterPublicKey = publicKey;
          }
          return Recipient.from(context, Address.fromSerialized(masterPublicKey), false);
        } catch (Exception e) {
          return Recipient.from(context, Address.fromSerialized(publicKey), false);
        }
      }
    }
  }

  private Recipient getMessageDestination(SignalServiceContent content, SignalServiceDataMessage message) {
    if (message.getGroupInfo().isPresent()) {
      return Recipient.from(context, Address.fromExternal(context, GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false)), false);
    } else {
      return Recipient.from(context, Address.fromExternal(context, content.getSender()), false);
    }
  }

  private Recipient getMessageMasterDestination(String publicKey) {
    if (!PublicKeyValidation.isValid(publicKey)) {
      return Recipient.from(context, Address.fromSerialized(publicKey), false);
    } else {
      String userPublicKey = TextSecurePreferences.getLocalNumber(context);
      Set<String> allUserDevices = org.whispersystems.signalservice.loki.protocol.multidevice.MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey);
      if (allUserDevices.contains(publicKey)) {
        return Recipient.from(context, Address.fromSerialized(userPublicKey), false);
      } else {
        try {
          // TODO: Burn this with fire when we can
          PromiseUtilities.timeout(LokiFileServerAPI.shared.getDeviceLinks(publicKey, false), 6000).get();
          String masterPublicKey = org.whispersystems.signalservice.loki.protocol.multidevice.MultiDeviceProtocol.shared.getMasterDevice(publicKey);
          if (masterPublicKey == null) {
            masterPublicKey = publicKey;
          }
          return Recipient.from(context, Address.fromSerialized(masterPublicKey), false);
        } catch (Exception e) {
          return Recipient.from(context, Address.fromSerialized(publicKey), false);
        }
      }
    }
  }

  private void notifyTypingStoppedFromIncomingMessage(@NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.from(context, Address.fromSerialized(sender), false);
    long      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, device, true);
    }
  }

  private boolean shouldIgnore(@Nullable SignalServiceContent content) {
    if (content == null) {
      Log.w(TAG, "Got a message with null content.");
      return true;
    }

    if (SessionMetaProtocol.shouldIgnoreMessage(content)) {
      Log.d("Loki", "Ignoring duplicate message.");
      return true;
    }

    Recipient sender = Recipient.from(context, Address.fromSerialized(content.getSender()), false);

    if (content.getDeviceLink().isPresent()) {
      return false;
    } else if (content.getDataMessage().isPresent()) {
      SignalServiceDataMessage message      = content.getDataMessage().get();
      Recipient                conversation = getMessageDestination(content, message);

      if (conversation.isGroupRecipient() && conversation.isBlocked()) {
        return true;
      } else if (conversation.isGroupRecipient()) {
        GroupDatabase    groupDatabase = DatabaseFactory.getGroupDatabase(context);
        Optional<String> groupId       = message.getGroupInfo().isPresent() ? Optional.of(GroupUtil.getEncodedId(message.getGroupInfo().get()))
                                                                            : Optional.absent();

        if (groupId.isPresent() && groupDatabase.isUnknownGroup(groupId.get())) {
          return false;
        }

        boolean isTextMessage    = message.getBody().isPresent();
        boolean isMediaMessage   = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent();
        boolean isExpireMessage  = message.isExpirationUpdate();
        boolean isContentMessage = !message.isGroupUpdate() && (isTextMessage || isMediaMessage || isExpireMessage);
        boolean isGroupActive    = groupId.isPresent() && groupDatabase.isActive(groupId.get());
        boolean isLeaveMessage   = message.getGroupInfo().isPresent() && message.getGroupInfo().get().getType() == SignalServiceGroup.Type.QUIT;

        boolean shouldIgnoreContentMessage = ClosedGroupsProtocol.shouldIgnoreContentMessage(context, conversation, groupId.orNull(), content);
        return (isContentMessage && !isGroupActive) || (sender.isBlocked() && !isLeaveMessage) || (isContentMessage && shouldIgnoreContentMessage);
      } else {
        return sender.isBlocked();
      }
    } else if (content.getCallMessage().isPresent() || content.getTypingMessage().isPresent()) {
      return sender.isBlocked();
    } else if (content.getSyncMessage().isPresent()) {
      return SyncMessagesProtocol.shouldIgnoreSyncMessage(context, sender);
    }

    return false;
  }

  private boolean isGroupChatMessage(SignalServiceContent content) {
    return content.getDataMessage().isPresent() && content.getDataMessage().get().isGroupMessage();
  }

  private void resetRecipientToPush(@NonNull Recipient recipient) {
    if (recipient.isForceSmsSelection()) {
      DatabaseFactory.getRecipientDatabase(context).setForceSmsSelection(recipient, false);
    }
  }

  private void forceStickerDownloadIfNecessary(List<DatabaseAttachment> stickerAttachments) {
    if (stickerAttachments.isEmpty()) return;

    DatabaseAttachment stickerAttachment = stickerAttachments.get(0);

    if (stickerAttachment.getTransferState() != AttachmentDatabase.TRANSFER_PROGRESS_DONE) {
      AttachmentDownloadJob downloadJob = new AttachmentDownloadJob(messageId, stickerAttachment.getAttachmentId(), true);

      try {
        ApplicationContext.getInstance(context).injectDependencies(downloadJob);
        downloadJob.setContext(context);
        downloadJob.doWork();
      } catch (Exception e) {
        Log.w(TAG, "Failed to download sticker inline. Scheduling.");
        ApplicationContext.getInstance(context).getJobManager().add(downloadJob);
      }
    }
  }

  @SuppressWarnings("WeakerAccess")
  private static class StorageFailedException extends Exception {
    private final String sender;
    private final int senderDevice;

    private StorageFailedException(Exception e, String sender, int senderDevice) {
      super(e);
      this.sender       = sender;
      this.senderDevice = senderDevice;
    }

    public String getSender() {
      return sender;
    }

    public int getSenderDevice() {
      return senderDevice;
    }
  }

  public static final class Factory implements Job.Factory<PushDecryptJob> {
    @Override
    public @NonNull PushDecryptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new PushDecryptJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getLong(KEY_SMS_MESSAGE_ID));
    }
  }
}