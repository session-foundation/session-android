package org.thoughtcrime.securesms.service;


import android.content.ComponentName;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.chooser.ChooserTarget;
import android.service.chooser.ChooserTargetService;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;

import org.jetbrains.annotations.NotNull;
import org.session.libsession.database.StorageProtocol;
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.ShareActivity;
import org.thoughtcrime.securesms.contacts.ShareContactListLoader;
import org.thoughtcrime.securesms.database.RecipientRepository;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.repository.ConversationRepository;
import org.thoughtcrime.securesms.util.BitmapUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class DirectShareService extends ChooserTargetService {
  @Inject
  RecipientRepository recipientRepository;

  @Inject
  LegacyGroupDeprecationManager legacyGroupDeprecationManager;

  @Inject
  StorageProtocol storage;

  @Inject
  ConversationRepository conversationRepository;

  private static final String TAG = DirectShareService.class.getSimpleName();

  @Override
  public List<ChooserTarget> onGetChooserTargets(ComponentName targetActivityName,
                                                 IntentFilter matchedFilter)
  {
    List<ChooserTarget> results        = new ArrayList<>();
    ComponentName       componentName  = new ComponentName(this, ShareActivity.class);

    List<@NotNull Recipient> items = new ShareContactListLoader(this, null, legacyGroupDeprecationManager, storage, conversationRepository).loadInBackground();

    for (final Recipient recipient : items) {
        Bitmap avatar;

        if (recipient.getAvatar() != null) {
            try {
                avatar = Glide.with(this)
                        .asBitmap()
                        .load(recipient.getAvatar())
                        .circleCrop()
                        .submit(getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                                getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width))
                        .get();
            } catch (InterruptedException | ExecutionException e) {
                Log.w(TAG, e);
                avatar = getFallbackDrawable(recipient);
            }
        } else {
            avatar = getFallbackDrawable(recipient);
        }

        Bundle bundle = new Bundle(1);
        bundle.putParcelable(ShareActivity.EXTRA_ADDRESS, recipient.getAddress());
        bundle.setClassLoader(getClassLoader());

        results.add(new ChooserTarget(recipient.getDisplayName(), Icon.createWithBitmap(avatar), 1.0f, componentName, bundle));
    }

    return results;
  }

  private Bitmap getFallbackDrawable(@NonNull Recipient recipient) {
      //TODO: Use proper color
    return BitmapUtil.createFromDrawable(new ColorDrawable(Color.RED),
                                         getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                                         getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height));
  }
}
