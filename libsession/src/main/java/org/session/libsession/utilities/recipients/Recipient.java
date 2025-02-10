/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 - 2017 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.session.libsession.utilities.recipients;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.function.Consumer;

import org.greenrobot.eventbus.EventBus;
import org.session.libsession.avatars.ContactColors;
import org.session.libsession.avatars.ContactPhoto;
import org.session.libsession.avatars.GroupRecordContactPhoto;
import org.session.libsession.avatars.ProfileContactPhoto;
import org.session.libsession.avatars.SystemContactPhoto;
import org.session.libsession.avatars.TransparentContactPhoto;
import org.session.libsession.database.StorageProtocol;
import org.session.libsession.messaging.MessagingModuleConfiguration;
import org.session.libsession.messaging.contacts.Contact;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.FutureTaskListener;
import org.session.libsession.utilities.GroupRecord;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsession.utilities.ListenableFutureTask;
import org.session.libsession.utilities.MaterialColor;
import org.session.libsession.utilities.ProfilePictureModifiedEvent;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.RecipientProvider.RecipientDetails;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.guava.Optional;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;

public class Recipient implements RecipientModifiedListener, Cloneable {

  private static final String            TAG      = Recipient.class.getSimpleName();
  private static final RecipientProvider provider = new RecipientProvider();

  private final Set<RecipientModifiedListener> listeners = Collections.newSetFromMap(new WeakHashMap<RecipientModifiedListener, Boolean>());

  private final @NonNull Address address;
  private final @NonNull List<Recipient> participants = new LinkedList<>();

  private final     Context context;
  private @Nullable String  name;
  private @Nullable String  customLabel;
  private           boolean resolving;
  private           boolean isLocalNumber;

  private @Nullable Uri                  systemContactPhoto;
  private @Nullable Long                 groupAvatarId;
  private           Uri                  contactUri;
  private @Nullable Uri                  messageRingtone         = null;
  private @Nullable Uri                  callRingtone            = null;
  public            long                 mutedUntil              = 0;
  public            int                  notifyType              = 0;
  private           boolean              autoDownloadAttachments = false;
  private           boolean              blocked                 = false;
  private           boolean              approved                = false;
  private           boolean              approvedMe              = false;
  private           DisappearingState    disappearingState     = null;
  private           VibrateState         messageVibrate          = VibrateState.DEFAULT;
  private           VibrateState         callVibrate             = VibrateState.DEFAULT;
  private           int                  expireMessages          = 0;
  private           Optional<Integer>    defaultSubscriptionId   = Optional.absent();
  private @NonNull  RegisteredState      registered              = RegisteredState.UNKNOWN;

  private @Nullable MaterialColor  color;
  private @Nullable byte[]         profileKey;
  private @Nullable String         profileName;
  private @Nullable String         profileAvatar;
  private           boolean        profileSharing;
  private           String         notificationChannel;
  private           boolean        forceSmsSelection;
  private           String         wrapperHash;
  private           boolean        blocksCommunityMessageRequests;

  private @NonNull  UnidentifiedAccessMode unidentifiedAccessMode = UnidentifiedAccessMode.ENABLED;

  @SuppressWarnings("ConstantConditions")
  public static @NonNull Recipient from(@NonNull Context context, @NonNull Address address, boolean asynchronous) {
    if (address == null) throw new AssertionError(address);
    return provider.getRecipient(context, address, Optional.absent(), Optional.absent(), asynchronous);
  }

  @SuppressWarnings("ConstantConditions")
  public static @NonNull Recipient from(@NonNull Context context, @NonNull Address address, @NonNull Optional<RecipientSettings> settings, @NonNull Optional<GroupRecord> groupRecord, boolean asynchronous) {
    if (address == null) throw new AssertionError(address);
    return provider.getRecipient(context, address, settings, groupRecord, asynchronous);
  }

  public static void applyCached(@NonNull Address address, Consumer<Recipient> consumer) {
    Optional<Recipient> recipient = provider.getCached(address);
    if (recipient.isPresent()) consumer.accept(recipient.get());
  }

  public static boolean removeCached(@NonNull Address address) {
    return provider.removeCached(address);
  }

  Recipient(@NonNull  Context context,
            @NonNull  Address address,
            @Nullable Recipient stale,
            @NonNull  Optional<RecipientDetails> details,
            @NonNull  ListenableFutureTask<RecipientDetails> future)
  {
    this.context   = context.getApplicationContext();
    this.address   = address;
    this.color     = null;
    this.resolving = true;

    if (stale != null) {
      this.name                   = stale.name;
      this.contactUri             = stale.contactUri;
      this.systemContactPhoto     = stale.systemContactPhoto;
      this.groupAvatarId          = stale.groupAvatarId;
      this.isLocalNumber          = stale.isLocalNumber;
      this.color                  = stale.color;
      this.customLabel            = stale.customLabel;
      this.messageRingtone        = stale.messageRingtone;
      this.callRingtone           = stale.callRingtone;
      this.mutedUntil             = stale.mutedUntil;
      this.blocked                = stale.blocked;
      this.approved               = stale.approved;
      this.approvedMe             = stale.approvedMe;
      this.messageVibrate         = stale.messageVibrate;
      this.callVibrate            = stale.callVibrate;
      this.expireMessages         = stale.expireMessages;
      this.defaultSubscriptionId  = stale.defaultSubscriptionId;
      this.registered             = stale.registered;
      this.notificationChannel    = stale.notificationChannel;
      this.profileKey             = stale.profileKey;
      this.profileName            = stale.profileName;
      this.profileAvatar          = stale.profileAvatar;
      this.profileSharing         = stale.profileSharing;
      this.unidentifiedAccessMode = stale.unidentifiedAccessMode;
      this.forceSmsSelection      = stale.forceSmsSelection;
      this.notifyType             = stale.notifyType;
      this.disappearingState      = stale.disappearingState;
      this.autoDownloadAttachments = stale.autoDownloadAttachments;

      this.participants.clear();
      this.participants.addAll(stale.participants);
    }

    if (details.isPresent()) {
      this.name                    = details.get().name;
      this.systemContactPhoto      = details.get().systemContactPhoto;
      this.groupAvatarId           = details.get().groupAvatarId;
      this.isLocalNumber           = details.get().isLocalNumber;
      this.color                   = details.get().color;
      this.messageRingtone         = details.get().messageRingtone;
      this.callRingtone            = details.get().callRingtone;
      this.mutedUntil              = details.get().mutedUntil;
      this.blocked                 = details.get().blocked;
      this.approved                = details.get().approved;
      this.approvedMe              = details.get().approvedMe;
      this.messageVibrate          = details.get().messageVibrateState;
      this.callVibrate             = details.get().callVibrateState;
      this.expireMessages          = details.get().expireMessages;
      this.defaultSubscriptionId   = details.get().defaultSubscriptionId;
      this.registered              = details.get().registered;
      this.notificationChannel     = details.get().notificationChannel;
      this.profileKey              = details.get().profileKey;
      this.profileName             = details.get().profileName;
      this.profileAvatar           = details.get().profileAvatar;
      this.profileSharing          = details.get().profileSharing;
      this.unidentifiedAccessMode  = details.get().unidentifiedAccessMode;
      this.forceSmsSelection       = details.get().forceSmsSelection;
      this.notifyType              = details.get().notifyType;
      this.autoDownloadAttachments = details.get().autoDownloadAttachments;
      this.blocksCommunityMessageRequests = details.get().blocksCommunityMessageRequests;
      this.disappearingState      = details.get().disappearingState;

      this.participants.clear();
      this.participants.addAll(details.get().participants);
    }

    future.addListener(new FutureTaskListener<RecipientDetails>() {
      @Override
      public void onSuccess(RecipientDetails result) {
        if (result != null) {
          synchronized (Recipient.this) {
            Recipient.this.name                   = result.name;
            Recipient.this.contactUri             = result.contactUri;
            Recipient.this.systemContactPhoto     = result.systemContactPhoto;
            Recipient.this.groupAvatarId          = result.groupAvatarId;
            Recipient.this.isLocalNumber          = result.isLocalNumber;
            Recipient.this.color                  = result.color;
            Recipient.this.customLabel            = result.customLabel;
            Recipient.this.messageRingtone        = result.messageRingtone;
            Recipient.this.callRingtone           = result.callRingtone;
            Recipient.this.mutedUntil             = result.mutedUntil;
            Recipient.this.blocked                = result.blocked;
            Recipient.this.approved               = result.approved;
            Recipient.this.approvedMe             = result.approvedMe;
            Recipient.this.messageVibrate         = result.messageVibrateState;
            Recipient.this.callVibrate            = result.callVibrateState;
            Recipient.this.expireMessages         = result.expireMessages;
            Recipient.this.defaultSubscriptionId  = result.defaultSubscriptionId;
            Recipient.this.registered             = result.registered;
            Recipient.this.notificationChannel    = result.notificationChannel;
            Recipient.this.profileKey             = result.profileKey;
            Recipient.this.profileName            = result.profileName;
            Recipient.this.profileAvatar          = result.profileAvatar;
            Recipient.this.profileSharing         = result.profileSharing;
            Recipient.this.unidentifiedAccessMode = result.unidentifiedAccessMode;
            Recipient.this.forceSmsSelection      = result.forceSmsSelection;
            Recipient.this.notifyType             = result.notifyType;
            Recipient.this.disappearingState      = result.disappearingState;
            Recipient.this.autoDownloadAttachments = result.autoDownloadAttachments;
            Recipient.this.blocksCommunityMessageRequests = result.blocksCommunityMessageRequests;


            Recipient.this.participants.clear();
            Recipient.this.participants.addAll(result.participants);
            Recipient.this.resolving = false;

            if (!listeners.isEmpty()) {
              for (Recipient recipient : participants) recipient.addListener(Recipient.this);
            }

            Recipient.this.notifyAll();
          }

          notifyListeners();
        }
      }

      @Override
      public void onFailure(ExecutionException error) {
        Log.w(TAG, error);
      }
    });
  }

  Recipient(@NonNull Context context, @NonNull Address address, @NonNull RecipientDetails details) {
    this.context                = context.getApplicationContext();
    this.address                = address;
    this.contactUri             = details.contactUri;
    this.name                   = details.name;
    this.systemContactPhoto     = details.systemContactPhoto;
    this.groupAvatarId          = details.groupAvatarId;
    this.isLocalNumber          = details.isLocalNumber;
    this.color                  = details.color;
    this.customLabel            = details.customLabel;
    this.messageRingtone        = details.messageRingtone;
    this.callRingtone           = details.callRingtone;
    this.mutedUntil             = details.mutedUntil;
    this.notifyType             = details.notifyType;
    this.autoDownloadAttachments = details.autoDownloadAttachments;
    this.blocked                = details.blocked;
    this.approved               = details.approved;
    this.approvedMe             = details.approvedMe;
    this.messageVibrate         = details.messageVibrateState;
    this.callVibrate            = details.callVibrateState;
    this.expireMessages         = details.expireMessages;
    this.defaultSubscriptionId  = details.defaultSubscriptionId;
    this.registered             = details.registered;
    this.notificationChannel    = details.notificationChannel;
    this.profileKey             = details.profileKey;
    this.profileName            = details.profileName;
    this.profileAvatar          = details.profileAvatar;
    this.profileSharing         = details.profileSharing;
    this.unidentifiedAccessMode = details.unidentifiedAccessMode;
    this.forceSmsSelection      = details.forceSmsSelection;
    this.wrapperHash            = details.wrapperHash;
    this.blocksCommunityMessageRequests = details.blocksCommunityMessageRequests;

    this.participants.addAll(details.participants);
    this.resolving    = false;
  }

  public boolean isLocalNumber() {
    return isLocalNumber;
  }

  public synchronized @Nullable Uri getContactUri() {
    return this.contactUri;
  }

  public void setContactUri(@Nullable Uri contactUri) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(contactUri, this.contactUri)) {
        this.contactUri = contactUri;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized @Nullable String getName() {
    StorageProtocol storage = MessagingModuleConfiguration.getShared().getStorage();
    String accountID = this.address.toString();
    if (isGroupOrCommunityRecipient()) {
      if (this.name == null) {
        List<String> names = new LinkedList<>();
        for (Recipient recipient : participants) {
          names.add(recipient.toShortString());
        }
        return Util.join(names, ", ");
      } else {
        return this.name;
      }
    } else if (isCommunityInboxRecipient()){
      String inboxID = GroupUtil.getDecodedOpenGroupInboxAccountId(accountID);
      Contact contact = storage.getContactWithAccountID(inboxID);
      if (contact == null) return accountID;
      return contact.displayName(Contact.ContactContext.REGULAR);
    } else {
      Contact contact = storage.getContactWithAccountID(accountID);
      if (contact == null) return null;
      return contact.displayName(Contact.ContactContext.REGULAR);
    }
  }

  public void setName(@Nullable String name) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.name, name)) {
        this.name = name;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public boolean getBlocksCommunityMessageRequests() {
    return blocksCommunityMessageRequests;
  }

  public void setBlocksCommunityMessageRequests(boolean blocksCommunityMessageRequests) {
    synchronized (this) {
      this.blocksCommunityMessageRequests = blocksCommunityMessageRequests;
    }

    notifyListeners();
  }

  public synchronized @NonNull MaterialColor getColor() {
    if      (isGroupOrCommunityRecipient()) return MaterialColor.GROUP;
    else if (color != null)      return color;
    else if (name != null)       return ContactColors.generateFor(name);
    else                         return ContactColors.UNKNOWN_COLOR;
  }

  public void setColor(@NonNull MaterialColor color) {
    synchronized (this) {
      this.color = color;
    }

    notifyListeners();
  }

  public @NonNull Address getAddress() {
    return address;
  }

  public synchronized @Nullable String getCustomLabel() {
    return customLabel;
  }

  public void setCustomLabel(@Nullable String customLabel) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(customLabel, this.customLabel)) {
        this.customLabel = customLabel;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized Optional<Integer> getDefaultSubscriptionId() {
    return defaultSubscriptionId;
  }

  public void setDefaultSubscriptionId(Optional<Integer> defaultSubscriptionId) {
    synchronized (this) {
      this.defaultSubscriptionId = defaultSubscriptionId;
    }

    notifyListeners();
  }

  public synchronized @Nullable String getProfileName() {
    return profileName;
  }

  public void setProfileName(@Nullable String profileName) {
    synchronized (this) {
      this.profileName = profileName;
    }

    notifyListeners();
  }

  public synchronized @Nullable String getProfileAvatar() {
    return profileAvatar;
  }

  public void setProfileAvatar(@Nullable String profileAvatar) {
    synchronized (this) {
      this.profileAvatar = profileAvatar;
    }

    notifyListeners();
    EventBus.getDefault().post(new ProfilePictureModifiedEvent(this));
  }

  public synchronized boolean isProfileSharing() {
    return profileSharing;
  }

  public void setProfileSharing(boolean value) {
    synchronized (this) {
      this.profileSharing = value;
    }

    notifyListeners();
  }

  public boolean isGroupOrCommunityRecipient() {
    return address.isGroupOrCommunity();
  }

  public boolean isContactRecipient() {
    return address.isContact();
  }
  public boolean is1on1() { return address.isContact() && !isLocalNumber; }

  public boolean isCommunityRecipient() {
    return address.isCommunity();
  }

  public boolean isCommunityOutboxRecipient() {
    return address.isCommunityOutbox();
  }

  public boolean isCommunityInboxRecipient() {
    return address.isCommunityInbox();
  }

  public boolean isLegacyGroupRecipient() {
    return address.isLegacyGroup();
  }

  public boolean isGroupRecipient() {
    return address.isGroup();
  }

  public boolean isGroupV2Recipient() {
    return address.isGroupV2();
  }


  @Deprecated
  public boolean isPushGroupRecipient() {
    return address.isGroupOrCommunity();
  }

  public @NonNull synchronized List<Recipient> getParticipants() {
    return new LinkedList<>(participants);
  }

  public void setParticipants(@NonNull List<Recipient> participants) {
    synchronized (this) {
      this.participants.clear();
      this.participants.addAll(participants);
    }

    notifyListeners();
  }

  public synchronized void addListener(RecipientModifiedListener listener) {
    if (listeners.isEmpty()) {
      for (Recipient recipient : participants) recipient.addListener(this);
    }
    listeners.add(listener);
  }

  public synchronized void removeListener(RecipientModifiedListener listener) {
    listeners.remove(listener);

    if (listeners.isEmpty()) {
      for (Recipient recipient : participants) recipient.removeListener(this);
    }
  }

  public synchronized String toShortString() {
    String name = getName();
    if (name != null) return name;
    String accountId = address.serialize();
    if (accountId.length() < 4) return accountId; // so substrings don't throw out of bounds exceptions
    int takeAmount = 4;
    String start = accountId.substring(0, takeAmount);
    String end = accountId.substring(accountId.length()-takeAmount);
    return start+"..."+end;
  }

  public synchronized @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return (new TransparentContactPhoto()).asDrawable(context, getColor().toAvatarColor(context), inverted);
  }

  public synchronized @Nullable ContactPhoto getContactPhoto() {
    if      (isLocalNumber)                               return new ProfileContactPhoto(address, String.valueOf(TextSecurePreferences.getProfileAvatarId(context)));
    else if (isGroupOrCommunityRecipient() && groupAvatarId != null) return new GroupRecordContactPhoto(address, groupAvatarId);
    else if (systemContactPhoto != null)                  return new SystemContactPhoto(address, systemContactPhoto, 0);
    else if (profileAvatar != null)                       return new ProfileContactPhoto(address, profileAvatar);
    else                                                  return null;
  }

  public void setSystemContactPhoto(@Nullable Uri systemContactPhoto) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(systemContactPhoto, this.systemContactPhoto)) {
        this.systemContactPhoto = systemContactPhoto;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public void setGroupAvatarId(@Nullable Long groupAvatarId) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.groupAvatarId, groupAvatarId)) {
        this.groupAvatarId = groupAvatarId;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  @Nullable
  public synchronized Long getGroupAvatarId() {
    return groupAvatarId;
  }

  public synchronized @Nullable Uri getMessageRingtone() {
    if (messageRingtone != null && messageRingtone.getScheme() != null && messageRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return messageRingtone;
  }

  public void setMessageRingtone(@Nullable Uri ringtone) {
    synchronized (this) {
      this.messageRingtone = ringtone;
    }

    notifyListeners();
  }

  public synchronized @Nullable Uri getCallRingtone() {
    if (callRingtone != null && callRingtone.getScheme() != null && callRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return callRingtone;
  }

  public void setCallRingtone(@Nullable Uri ringtone) {
    synchronized (this) {
      this.callRingtone = ringtone;
    }

    notifyListeners();
  }

  public synchronized boolean isMuted() {
    return System.currentTimeMillis() <= mutedUntil;
  }

  public void setMuted(long mutedUntil) {
    synchronized (this) {
      this.mutedUntil = mutedUntil;
    }

    notifyListeners();
  }

  public void setNotifyType(int notifyType) {
    synchronized (this) {
      this.notifyType = notifyType;
    }

    notifyListeners();
  }

  public boolean getAutoDownloadAttachments() {
    return autoDownloadAttachments;
  }

  public void setAutoDownloadAttachments(boolean autoDownloadAttachments) {
    synchronized (this) {
      this.autoDownloadAttachments = autoDownloadAttachments;
    }

    notifyListeners();
  }

  public synchronized boolean isBlocked() {
    return blocked;
  }

  public void setBlocked(boolean blocked) {
    synchronized (this) {
      this.blocked = blocked;
    }

    notifyListeners();
  }

  public synchronized boolean isApproved() {
    return approved;
  }

  public void setApproved(boolean approved) {
    synchronized (this) {
      this.approved = approved;
    }

    notifyListeners();
  }

  public synchronized boolean hasApprovedMe() {
    return approvedMe;
  }

  public void setHasApprovedMe(boolean approvedMe) {
    synchronized (this) {
      this.approvedMe = approvedMe;
    }

    notifyListeners();
  }

  public synchronized VibrateState getMessageVibrate() {
    return messageVibrate;
  }

  public void setMessageVibrate(VibrateState vibrate) {
    synchronized (this) {
      this.messageVibrate = vibrate;
    }

    notifyListeners();
  }

  public synchronized  VibrateState getCallVibrate() {
    return callVibrate;
  }

  public void setCallVibrate(VibrateState vibrate) {
    synchronized (this) {
      this.callVibrate = vibrate;
    }

    notifyListeners();
  }

  public synchronized int getExpireMessages() {
    return expireMessages;
  }

  public void setExpireMessages(int expireMessages) {
    synchronized (this) {
      this.expireMessages = expireMessages;
    }

    notifyListeners();
  }

  public synchronized DisappearingState getDisappearingState() {
    return disappearingState;
  }

  public void setDisappearingState(DisappearingState disappearingState) {
    synchronized (this) {
      this.disappearingState = disappearingState;
    }

    notifyListeners();
  }

  public synchronized RegisteredState getRegistered() {
    if      (isPushGroupRecipient()) return RegisteredState.REGISTERED;

    return registered;
  }

  public void setRegistered(@NonNull RegisteredState value) {
    boolean notify = false;

    synchronized (this) {
      if (this.registered != value) {
        this.registered = value;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized @Nullable String getNotificationChannel() {
    return notificationChannel;
  }

  public void setNotificationChannel(@Nullable String value) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.notificationChannel, value)) {
        this.notificationChannel = value;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public boolean isForceSmsSelection() {
    return forceSmsSelection;
  }

  public void setForceSmsSelection(boolean value) {
    synchronized (this) {
      this.forceSmsSelection = value;
    }

    notifyListeners();
  }

  public synchronized @Nullable byte[] getProfileKey() {
    return profileKey;
  }

  public void setProfileKey(@Nullable byte[] profileKey) {
    synchronized (this) {
      this.profileKey = profileKey;
    }

    notifyListeners();
  }

  public @NonNull synchronized UnidentifiedAccessMode getUnidentifiedAccessMode() {
    return unidentifiedAccessMode;
  }

  public String getWrapperHash() {
    return wrapperHash;
  }

  public void setWrapperHash(String wrapperHash) {
    this.wrapperHash = wrapperHash;
  }

  public void setUnidentifiedAccessMode(@NonNull UnidentifiedAccessMode unidentifiedAccessMode) {
    synchronized (this) {
      this.unidentifiedAccessMode = unidentifiedAccessMode;
    }

    notifyListeners();
  }

  public synchronized boolean isSystemContact() {
    return contactUri != null;
  }

  public synchronized Recipient resolve() {
    while (resolving) Util.wait(this, 0);
    return this;
  }

  public synchronized boolean showCallMenu() {
    return !isGroupOrCommunityRecipient() && hasApprovedMe();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Recipient recipient = (Recipient) o;
    return resolving == recipient.resolving
            && mutedUntil == recipient.mutedUntil
            && notifyType == recipient.notifyType
            && blocked == recipient.blocked
            && approved == recipient.approved
            && approvedMe == recipient.approvedMe
            && expireMessages == recipient.expireMessages
            && address.equals(recipient.address)
            && Objects.equals(name, recipient.name)
            && Objects.equals(customLabel, recipient.customLabel)
            && Objects.equals(groupAvatarId, recipient.groupAvatarId)
            && Arrays.equals(profileKey, recipient.profileKey)
            && Objects.equals(profileName, recipient.profileName)
            && Objects.equals(profileAvatar, recipient.profileAvatar)
            && Objects.equals(wrapperHash, recipient.wrapperHash)
            && blocksCommunityMessageRequests == recipient.blocksCommunityMessageRequests;
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(
            address,
            name,
            customLabel,
            resolving,
            groupAvatarId,
            mutedUntil,
            notifyType,
            blocked,
            approved,
            approvedMe,
            expireMessages,
            profileName,
            profileAvatar,
            wrapperHash,
            blocksCommunityMessageRequests
    );
    result = 31 * result + Arrays.hashCode(profileKey);
    return result;
  }

  public void notifyListeners() {
    Set<RecipientModifiedListener> localListeners;

    synchronized (this) {
      localListeners = new HashSet<>(listeners);
    }

    for (RecipientModifiedListener listener : localListeners)
      listener.onModified(this);
  }

  @Override
  public void onModified(Recipient recipient) {
    notifyListeners();
  }

  public synchronized boolean isResolving() {
    return resolving;
  }

  public enum VibrateState {
    DEFAULT(0), ENABLED(1), DISABLED(2);

    private final int id;

    VibrateState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static VibrateState fromId(int id) {
      return values()[id];
    }
  }

  public enum DisappearingState {
    LEGACY(0), UPDATED(1);

    private final int id;

    DisappearingState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static DisappearingState fromId(int id) {
      return values()[id];
    }
  }

  public enum RegisteredState {
    UNKNOWN(0), REGISTERED(1), NOT_REGISTERED(2);

    private final int id;

    RegisteredState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static RegisteredState fromId(int id) {
      return values()[id];
    }
  }

  public enum UnidentifiedAccessMode {
    UNKNOWN(0), DISABLED(1), ENABLED(2), UNRESTRICTED(3);

    private final int mode;

    UnidentifiedAccessMode(int mode) {
      this.mode = mode;
    }

    public int getMode() {
      return mode;
    }

    public static UnidentifiedAccessMode fromMode(int mode) {
      return values()[mode];
    }
  }

  public static class RecipientSettings {
    private final boolean                blocked;
    private final boolean                approved;
    private final boolean                approvedMe;
    private final long                   muteUntil;
    private final int                    notifyType;
    private final boolean                autoDownloadAttachments;
    private final DisappearingState      disappearingState;
    private final VibrateState           messageVibrateState;
    private final VibrateState           callVibrateState;
    private final Uri                    messageRingtone;
    private final Uri                    callRingtone;
    private final MaterialColor          color;
    private final int                    defaultSubscriptionId;
    private final int                    expireMessages;
    private final RegisteredState        registered;
    private final byte[]                 profileKey;
    private final String                 systemDisplayName;
    private final String                 systemContactPhoto;
    private final String                 systemPhoneLabel;
    private final String                 systemContactUri;
    private final String                 signalProfileName;
    private final String                 signalProfileAvatar;
    private final boolean                profileSharing;
    private final String                 notificationChannel;
    private final UnidentifiedAccessMode unidentifiedAccessMode;
    private final boolean                forceSmsSelection;
    private final String                 wrapperHash;
    private final boolean                blocksCommunityMessageRequests;

    public RecipientSettings(boolean blocked, boolean approved, boolean approvedMe, long muteUntil,
                             int notifyType,
                             boolean autoDownloadAttachments,
                      @NonNull DisappearingState disappearingState,
                      @NonNull VibrateState messageVibrateState,
                      @NonNull VibrateState callVibrateState,
                      @Nullable Uri messageRingtone,
                      @Nullable Uri callRingtone,
                      @Nullable MaterialColor color,
                      int defaultSubscriptionId,
                      int expireMessages,
                      @NonNull RegisteredState registered,
                      @Nullable byte[] profileKey,
                      @Nullable String systemDisplayName,
                      @Nullable String systemContactPhoto,
                      @Nullable String systemPhoneLabel,
                      @Nullable String systemContactUri,
                      @Nullable String signalProfileName,
                      @Nullable String signalProfileAvatar,
                      boolean profileSharing,
                      @Nullable String notificationChannel,
                      @NonNull UnidentifiedAccessMode unidentifiedAccessMode,
                      boolean forceSmsSelection,
                      String wrapperHash,
                             boolean blocksCommunityMessageRequests
    )
    {
      this.blocked                 = blocked;
      this.approved                = approved;
      this.approvedMe              = approvedMe;
      this.muteUntil               = muteUntil;
      this.notifyType              = notifyType;
      this.autoDownloadAttachments = autoDownloadAttachments;
      this.disappearingState      = disappearingState;
      this.messageVibrateState     = messageVibrateState;
      this.callVibrateState        = callVibrateState;
      this.messageRingtone         = messageRingtone;
      this.callRingtone            = callRingtone;
      this.color                   = color;
      this.defaultSubscriptionId   = defaultSubscriptionId;
      this.expireMessages          = expireMessages;
      this.registered              = registered;
      this.profileKey              = profileKey;
      this.systemDisplayName       = systemDisplayName;
      this.systemContactPhoto      = systemContactPhoto;
      this.systemPhoneLabel        = systemPhoneLabel;
      this.systemContactUri        = systemContactUri;
      this.signalProfileName       = signalProfileName;
      this.signalProfileAvatar     = signalProfileAvatar;
      this.profileSharing          = profileSharing;
      this.notificationChannel     = notificationChannel;
      this.unidentifiedAccessMode  = unidentifiedAccessMode;
      this.forceSmsSelection       = forceSmsSelection;
      this.wrapperHash            = wrapperHash;
      this.blocksCommunityMessageRequests = blocksCommunityMessageRequests;
    }

    public @Nullable MaterialColor getColor() {
      return color;
    }

    public boolean isBlocked() {
      return blocked;
    }

    public boolean isApproved() {
      return approved;
    }

    public boolean hasApprovedMe() {
      return approvedMe;
    }

    public long getMuteUntil() {
      return muteUntil;
    }

    public int getNotifyType() {
      return notifyType;
    }

    public @NonNull DisappearingState getDisappearingState() {
      return disappearingState;
    }

    public boolean getAutoDownloadAttachments() {
      return autoDownloadAttachments;
    }

    public @NonNull VibrateState getMessageVibrateState() {
      return messageVibrateState;
    }

    public @NonNull VibrateState getCallVibrateState() {
      return callVibrateState;
    }

    public @Nullable Uri getMessageRingtone() {
      return messageRingtone;
    }

    public @Nullable Uri getCallRingtone() {
      return callRingtone;
    }

    public Optional<Integer> getDefaultSubscriptionId() {
      return defaultSubscriptionId != -1 ? Optional.of(defaultSubscriptionId) : Optional.absent();
    }

    public int getExpireMessages() {
      return expireMessages;
    }

    public RegisteredState getRegistered() {
      return registered;
    }

    public @Nullable byte[] getProfileKey() {
      return profileKey;
    }

    public @Nullable String getSystemDisplayName() {
      return systemDisplayName;
    }

    public @Nullable String getSystemContactPhotoUri() {
      return systemContactPhoto;
    }

    public @Nullable String getSystemPhoneLabel() {
      return systemPhoneLabel;
    }

    public @Nullable String getSystemContactUri() {
      return systemContactUri;
    }

    public @Nullable String getProfileName() {
      return signalProfileName;
    }

    public @Nullable String getProfileAvatar() {
      return signalProfileAvatar;
    }

    public boolean isProfileSharing() {
      return profileSharing;
    }

    public @Nullable String getNotificationChannel() {
      return notificationChannel;
    }

    public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
      return unidentifiedAccessMode;
    }

    public boolean isForceSmsSelection() {
      return forceSmsSelection;
    }

    public String getWrapperHash() {
      return wrapperHash;
    }

    public boolean getBlocksCommunityMessageRequests() {
      return blocksCommunityMessageRequests;
    }

  }

  @NonNull
  @Override
  public Recipient clone() throws CloneNotSupportedException {
    return (Recipient) super.clone();
  }
}
