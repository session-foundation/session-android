package org.thoughtcrime.securesms.notifications;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier;
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager;
import org.session.libsession.messaging.sending_receiving.pollers.Poller;
import org.session.libsession.messaging.sending_receiving.pollers.PollerManager;
import org.session.libsession.utilities.Debouncer;
import org.session.libsignal.utilities.ThreadUtils;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.util.AvatarUtils;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import kotlin.Unit;

@Singleton
public class OptimizedMessageNotifier implements MessageNotifier {
  private final MessageNotifier         wrapped;
  private final Debouncer               debouncer;

  private final OpenGroupPollerManager  openGroupPollerManager;

  private final PollerManager pollerManager;

  @Inject
  public OptimizedMessageNotifier(AvatarUtils avatarUtils,
                                  OpenGroupPollerManager openGroupPollerManager,
                                  PollerManager pollerManager,
                                  DefaultMessageNotifier defaultMessageNotifier) {
    this.wrapped   = defaultMessageNotifier;
    this.openGroupPollerManager = openGroupPollerManager;
    this.debouncer = new Debouncer(TimeUnit.SECONDS.toMillis(2));
    this.pollerManager = pollerManager;
  }

  @Override
  public void setVisibleThread(long threadId) { wrapped.setVisibleThread(threadId); }

  @Override
  public void setHomeScreenVisible(boolean isVisible) {
    wrapped.setHomeScreenVisible(isVisible);
  }

  @Override
  public void setLastDesktopActivityTimestamp(long timestamp) { wrapped.setLastDesktopActivityTimestamp(timestamp);}


  @Override
  public void cancelDelayedNotifications() { wrapped.cancelDelayedNotifications(); }

  @Override
  public void updateNotification(@NonNull Context context) {
    boolean isCaughtUp = true;
    isCaughtUp = isCaughtUp && !pollerManager.isPolling();

    isCaughtUp = isCaughtUp && openGroupPollerManager.isAllCaughtUp();

    if (isCaughtUp) {
      performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context));
    } else {
      debouncer.publish(() -> performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context)));
    }
  }

  @Override
  public void updateNotification(@NonNull Context context, long threadId) {
    boolean isCaughtUp = true;
    isCaughtUp = isCaughtUp && !pollerManager.isPolling();

    isCaughtUp = isCaughtUp && openGroupPollerManager.isAllCaughtUp();
    
    if (isCaughtUp) {
      performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context, threadId));
    } else {
      debouncer.publish(() -> performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context, threadId)));
    }
  }

  @Override
  public void updateNotification(@NonNull Context context, long threadId, boolean signal) {
    boolean isCaughtUp = true;
    isCaughtUp = isCaughtUp && !pollerManager.isPolling();

    isCaughtUp = isCaughtUp && openGroupPollerManager.isAllCaughtUp();

    if (isCaughtUp) {
      performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context, threadId, signal));
    } else {
      debouncer.publish(() -> performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context, threadId, signal)));
    }
  }

  @Override
  public void updateNotification(@androidx.annotation.NonNull Context context, boolean signal, int reminderCount) {
    boolean isCaughtUp = true;
    isCaughtUp = isCaughtUp && !pollerManager.isPolling();

    isCaughtUp = isCaughtUp && openGroupPollerManager.isAllCaughtUp();

    if (isCaughtUp) {
      performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context, signal, reminderCount));
    } else {
      debouncer.publish(() -> performOnBackgroundThreadIfNeeded(() -> wrapped.updateNotification(context, signal, reminderCount)));
    }
  }

  @Override
  public void clearReminder(@NonNull Context context) { wrapped.clearReminder(context); }

  private void performOnBackgroundThreadIfNeeded(Runnable r) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      ThreadUtils.queue(() -> {
        r.run();
        return Unit.INSTANCE;
      });
    } else {
      r.run();
    }
  }
}
