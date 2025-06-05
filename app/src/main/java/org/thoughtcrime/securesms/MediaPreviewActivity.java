/*
 * Copyright (C) 2014 Open Whisper Systems
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
package org.thoughtcrime.securesms;

import static org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.CursorIndexOutOfBoundsException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.core.util.Pair;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.squareup.phrase.Phrase;

import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager;
import org.session.libsession.messaging.messages.control.DataExtractionNotification;
import org.session.libsession.messaging.sending_receiving.MessageSender;
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment;
import org.session.libsession.snode.SnodeAPI;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.RecipientModifiedListener;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.components.MediaView;
import org.thoughtcrime.securesms.components.dialogs.DeleteMediaPreviewDialog;
import org.thoughtcrime.securesms.database.MediaDatabase.MediaRecord;
import org.thoughtcrime.securesms.database.loaders.PagingMediaLoader;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.media.MediaOverviewActivity;
import org.thoughtcrime.securesms.mediapreview.MediaPreviewViewModel;
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.AttachmentUtil;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.FilenameUtils;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.SaveAttachmentTask.Attachment;

import java.io.IOException;
import java.util.Locale;
import java.util.WeakHashMap;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import kotlin.Unit;
import network.loki.messenger.R;
import network.loki.messenger.databinding.MediaPreviewActivityBinding;
import network.loki.messenger.databinding.MediaViewPageBinding;


/**
 * Activity for displaying media attachments in-app
 */
@AndroidEntryPoint
public class MediaPreviewActivity extends ScreenLockActionBarActivity implements RecipientModifiedListener,
                                                                                 LoaderManager.LoaderCallbacks<Pair<Cursor, Integer>>,
                                                                                 MediaRailAdapter.RailItemListener
{
  private final static String TAG = MediaPreviewActivity.class.getSimpleName();

  private static final int UI_ANIMATION_DELAY     = 300;

  public static final String ADDRESS_EXTRA        = "address";
  public static final String DATE_EXTRA           = "date";
  public static final String SIZE_EXTRA           = "size";
  public static final String CAPTION_EXTRA        = "caption";
  public static final String OUTGOING_EXTRA       = "outgoing";
  public static final String LEFT_IS_RECENT_EXTRA = "left_is_recent";

  private MediaPreviewActivityBinding binding;
  private Uri                   initialMediaUri;
  private String                initialMediaType;
  private long                  initialMediaSize;
  private String                initialCaption;
  private Recipient             conversationRecipient;
  private boolean               leftIsRecent;
  private MediaPreviewViewModel viewModel;
  private ViewPagerListener     viewPagerListener;

  @Inject
  LegacyGroupDeprecationManager deprecationManager;

  private int restartItem = -1;

  private boolean isFullscreen = false;
  private final Handler hideHandler = new Handler(Looper.myLooper());

  @Inject DateUtils dateUtils;

  private final Runnable showRunnable = () -> {
    getSupportActionBar().show();
  };
  private final Runnable hideRunnable = () -> {
      WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
              .hide(WindowInsetsCompat.Type.systemBars());
  };
  private MediaItemAdapter adapter;
  private MediaRailAdapter albumRailAdapter;

  public static Intent getPreviewIntent(Context context, MediaPreviewArgs args) {
    return getPreviewIntent(context, args.getSlide(), args.getMmsRecord(), args.getThread());
  }

  public static Intent getPreviewIntent(Context context, Slide slide, MmsMessageRecord mms, Recipient threadRecipient) {
    Intent previewIntent = null;
    if (MediaPreviewActivity.isContentTypeSupported(slide.getContentType()) && slide.getUri() != null) {
      previewIntent = new Intent(context, MediaPreviewActivity.class);
      previewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              .setDataAndType(slide.getUri(), slide.getContentType())
              .putExtra(ADDRESS_EXTRA, threadRecipient.getAddress())
              .putExtra(OUTGOING_EXTRA, mms.isOutgoing())
              .putExtra(DATE_EXTRA, mms.getTimestamp())
              .putExtra(SIZE_EXTRA, slide.asAttachment().getSize())
              .putExtra(CAPTION_EXTRA, slide.getCaption().orNull())
              .putExtra(LEFT_IS_RECENT_EXTRA, false);
    }
    return previewIntent;
  }


  @SuppressWarnings("ConstantConditions")
  @Override
  protected void onCreate(Bundle bundle, boolean ready) {
    viewModel = new ViewModelProvider(this).get(MediaPreviewViewModel.class);
    binding = MediaPreviewActivityBinding.inflate(getLayoutInflater());

    setContentView(binding.getRoot());

    initializeViews();
    initializeResources();
    initializeObservers();
  }

  private void toggleFullscreen() {
    if (isFullscreen) {
      exitFullscreen();
    } else {
      enterFullscreen();
    }
  }

  private void enterFullscreen() {
    getSupportActionBar().hide();
    isFullscreen = true;
    hideHandler.removeCallbacks(showRunnable);
    hideHandler.postDelayed(hideRunnable, UI_ANIMATION_DELAY);
  }

  private void exitFullscreen() {
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
            .show(WindowInsetsCompat.Type.systemBars());

    isFullscreen = false;
    hideHandler.removeCallbacks(hideRunnable);
    hideHandler.postDelayed(showRunnable, UI_ANIMATION_DELAY);
  }

  @SuppressLint("MissingSuperCall")
  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  public void onModified(Recipient recipient) {
    Util.runOnMain(this::updateActionBar);
  }

  @Override
  public void onRailItemClicked(int distanceFromActive) {
    binding.mediaPager.setCurrentItem(binding.mediaPager.getCurrentItem() + distanceFromActive);
  }

  @Override
  public void onRailItemDeleteClicked(int distanceFromActive) {
    throw new UnsupportedOperationException("Callback unsupported.");
  }

  @SuppressWarnings("ConstantConditions")
  private void updateActionBar() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      CharSequence relativeTimeSpan;

      if (mediaItem.date > 0) {
        relativeTimeSpan = dateUtils.getDisplayFormattedTimeSpanString(Locale.getDefault(), mediaItem.date);
      } else {
        relativeTimeSpan = getString(R.string.draft);
      }

      if      (mediaItem.outgoing)          getSupportActionBar().setTitle(getString(R.string.you));
      else if (mediaItem.recipient != null) getSupportActionBar().setTitle(mediaItem.recipient.getName());
      else                                  getSupportActionBar().setTitle("");

      getSupportActionBar().setSubtitle(relativeTimeSpan);
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    initializeMedia();
  }

  @Override
  public void onPause() {
    super.onPause();
    restartItem = cleanupMedia();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    initializeResources();
  }

  private void initializeViews() {
    binding.mediaPager.setOffscreenPageLimit(1);

    albumRailAdapter = new MediaRailAdapter(Glide.with(this), this, false);

    binding.mediaPreviewAlbumRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
    binding.mediaPreviewAlbumRail.setAdapter(albumRailAdapter);

    setSupportActionBar(findViewById(R.id.search_toolbar));
    ActionBar actionBar = getSupportActionBar();
    actionBar.setDisplayHomeAsUpEnabled(true);
    actionBar.setHomeButtonEnabled(true);

    binding.mediaPager.setOnClickListener((v) -> {
      toggleFullscreen();
    });
  }

  private void initializeResources() {
    Address address = getIntent().getParcelableExtra(ADDRESS_EXTRA);

    initialMediaUri  = getIntent().getData();
    initialMediaType = getIntent().getType();
    initialMediaSize = getIntent().getLongExtra(SIZE_EXTRA, 0);
    initialCaption   = getIntent().getStringExtra(CAPTION_EXTRA);
    leftIsRecent     = getIntent().getBooleanExtra(LEFT_IS_RECENT_EXTRA, false);
    restartItem      = -1;

    if (address != null) {
      conversationRecipient = Recipient.from(this, address, true);
    } else {
      conversationRecipient = null;
    }
  }

  private void initializeObservers() {
    viewModel.getPreviewData().observe(this, previewData -> {
      if (previewData == null || binding == null || binding.mediaPager.getAdapter() == null) {
        return;
      }

      View playbackControls = ((MediaItemAdapter) binding.mediaPager.getAdapter()).getPlaybackControls(binding.mediaPager.getCurrentItem());

      if (previewData.getAlbumThumbnails().isEmpty() && previewData.getCaption() == null && playbackControls == null) {
        binding.mediaPreviewDetailsContainer.setVisibility(View.GONE);
      } else {
        binding.mediaPreviewDetailsContainer.setVisibility(View.VISIBLE);
      }

      binding.mediaPreviewAlbumRail.setVisibility(previewData.getAlbumThumbnails().isEmpty() ? View.GONE : View.VISIBLE);
      albumRailAdapter.setMedia(previewData.getAlbumThumbnails(), previewData.getActivePosition());
      binding.mediaPreviewAlbumRail.smoothScrollToPosition(previewData.getActivePosition());

      binding.mediaPreviewCaptionContainer.setVisibility(previewData.getCaption() == null ? View.GONE : View.VISIBLE);
      binding.mediaPreviewCaption.setText(previewData.getCaption());

      if (playbackControls != null) {
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playbackControls.setLayoutParams(params);

        binding.mediaPreviewPlaybackControlsContainer.removeAllViews();
        binding.mediaPreviewPlaybackControlsContainer.addView(playbackControls);
      } else {
        binding.mediaPreviewPlaybackControlsContainer.removeAllViews();
      }
    });
  }

  private void initializeMedia() {
    if (!isContentTypeSupported(initialMediaType)) {
      Log.w(TAG, "Unsupported media type sent to MediaPreviewActivity, finishing.");
      Toast.makeText(getApplicationContext(), R.string.attachmentsErrorNotSupported, Toast.LENGTH_LONG).show();
      finish();
    }

    Log.i(TAG, "Loading Part URI: " + initialMediaUri);

    if (conversationRecipient != null) {
      getSupportLoaderManager().restartLoader(0, null, this);
    } else {
      adapter = new SingleItemPagerAdapter(Glide.with(this), getWindow(), initialMediaUri, initialMediaType, initialMediaSize);
      binding.mediaPager.setAdapter(adapter);

      if (initialCaption != null) {
        binding.mediaPreviewDetailsContainer.setVisibility(View.VISIBLE);
        binding.mediaPreviewCaptionContainer.setVisibility(View.VISIBLE);
        binding.mediaPreviewCaption.setText(initialCaption);
      }
    }
  }

  private int cleanupMedia() {
    int restartItem = binding.mediaPager.getCurrentItem();

    binding.mediaPager.removeAllViews();
    binding.mediaPager.setAdapter(null);

    return restartItem;
  }

  private void showOverview() {
    startActivity(MediaOverviewActivity.createIntent(this, conversationRecipient.getAddress()));
  }

  private void forward() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      Intent composeIntent = new Intent(this, ShareActivity.class);
      composeIntent.putExtra(Intent.EXTRA_STREAM, mediaItem.uri);
      composeIntent.setType(mediaItem.mimeType);
      startActivity(composeIntent);
    }
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void saveToDisk() {
    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem == null) {
      Log.w(TAG, "Cannot save a null MediaItem to disk - bailing.");
      return;
    }

    // If we have an attachment then we can take the filename from it, otherwise we have to take the
    // more expensive route of looking up or synthesizing a filename from the MediaItem's Uri.
    String mediaFilename = "";
    if (mediaItem.attachment != null) {
      mediaFilename = mediaItem.attachment.getFilename();
    }

    if(mediaFilename == null || mediaFilename.isEmpty()){
      mediaFilename = FilenameUtils.getFilenameFromUri(MediaPreviewActivity.this, mediaItem.uri, mediaItem.mimeType);
    }

    final String outputFilename = mediaFilename; // We need a `final` value for the saveTask, below
    Log.i(TAG, "About to save media as: " + outputFilename);

    SaveAttachmentTask.showOneTimeWarningDialogOrSave(this, 1, () -> {
      Permissions.with(this)
              .request(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              .maxSdkVersion(Build.VERSION_CODES.P) // Note: P is API 28
              .withPermanentDenialDialog(getPermanentlyDeniedStorageText())
              .onAnyDenied(() -> {
                Toast.makeText(this, getPermanentlyDeniedStorageText(), Toast.LENGTH_LONG).show();
              })
              .onAllGranted(() -> {
                SaveAttachmentTask saveTask = new SaveAttachmentTask(MediaPreviewActivity.this);
                long saveDate = (mediaItem.date > 0) ? mediaItem.date : SnodeAPI.getNowWithOffset();
                saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new Attachment(mediaItem.uri, mediaItem.mimeType, saveDate, outputFilename));
                if (!mediaItem.outgoing) { sendMediaSavedNotificationIfNeeded(); }
              })
              .execute();
      return Unit.INSTANCE;
    });
  }

  private String getPermanentlyDeniedStorageText(){
      return Phrase.from(getApplicationContext(), R.string.permissionsStorageDeniedLegacy)
              .put(APP_NAME_KEY, getString(R.string.app_name))
              .format().toString();
  }

  private void sendMediaSavedNotificationIfNeeded() {
    if (conversationRecipient.isGroupOrCommunityRecipient()) return;
    DataExtractionNotification message = new DataExtractionNotification(new DataExtractionNotification.Kind.MediaSaved(SnodeAPI.getNowWithOffset()));
    MessageSender.send(message, conversationRecipient.getAddress());
  }

  @SuppressLint("StaticFieldLeak")
  private void deleteMedia() {
    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem == null || mediaItem.attachment == null) {
      return;
    }

    DeleteMediaPreviewDialog.show(this, () -> {
            new AsyncTask<Void, Void, Void>() {
              @Override
              protected Void doInBackground(Void... voids) {
                DatabaseAttachment attachment = mediaItem.attachment;
                if (attachment != null) {
                  AttachmentUtil.deleteAttachment(getApplicationContext(), attachment);
                }
                return null;
              }
            }.execute();

      finish();
    });
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);

    menu.clear();
    MenuInflater inflater = this.getMenuInflater();
    inflater.inflate(R.menu.media_preview, menu);

    final boolean isDeprecatedLegacyGroup = conversationRecipient != null &&
            conversationRecipient.isLegacyGroupRecipient() &&
            deprecationManager.getDeprecationState().getValue() == LegacyGroupDeprecationManager.DeprecationState.DEPRECATED;

    if (!isMediaInDb() || isDeprecatedLegacyGroup) {
      menu.findItem(R.id.media_preview__overview).setVisible(false);
      menu.findItem(R.id.delete).setVisible(false);
    }

    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      // TODO / WARNING: R.id values are NON-CONSTANT in Gradle 8.0+ - what would be the best way to address this?! -AL 2024/08/26
      case R.id.media_preview__overview: showOverview(); return true;
      case R.id.media_preview__forward:  forward();      return true;
      case R.id.save:                    saveToDisk();   return true;
      case R.id.delete:                  deleteMedia();  return true;
      case android.R.id.home:            finish();       return true;
    }

    return false;
  }

  private boolean isMediaInDb() {
    return conversationRecipient != null;
  }

  private @Nullable MediaItem getCurrentMediaItem() {
    if (adapter == null) return null;
    return adapter.getMediaItemFor(binding.mediaPager.getCurrentItem());
  }

  public static boolean isContentTypeSupported(final String contentType) {
    return contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video/"));
  }

  @Override
  public @NonNull Loader<Pair<Cursor, Integer>> onCreateLoader(int id, Bundle args) {
    return new PagingMediaLoader(this, conversationRecipient, initialMediaUri, leftIsRecent);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Pair<Cursor, Integer>> loader, @Nullable Pair<Cursor, Integer> data) {
    if (data == null) return;

    binding.mediaPager.unregisterOnPageChangeCallback(viewPagerListener);

    adapter = new CursorPagerAdapter(this, Glide.with(this), getWindow(), data.first, data.second, leftIsRecent);
    binding.mediaPager.setAdapter(adapter);

    final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        toggleFullscreen();
        return super.onSingleTapConfirmed(e);
      }
    });

    binding.observeTouchEventFrame.setOnTouchDispatchListener((view, event) -> {
      detector.onTouchEvent(event);
      return false;
    });

    viewModel.setCursor(this, data.first, leftIsRecent);

    int item = restartItem >= 0  && restartItem < adapter.getItemCount() ? restartItem : Math.max(Math.min(data.second, adapter.getItemCount() - 1), 0);

    viewPagerListener = new ViewPagerListener();
    binding.mediaPager.registerOnPageChangeCallback(viewPagerListener);

    try {
      binding.mediaPager.setCurrentItem(item, false);
    } catch (CursorIndexOutOfBoundsException e) {
      throw new RuntimeException("restartItem = " + restartItem + ", data.second = " + data.second + " leftIsRecent = " + leftIsRecent, e);
    }

    if (item == 0) { viewPagerListener.onPageSelected(0); }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Pair<Cursor, Integer>> loader) { /* Do nothing */  }

  private class ViewPagerListener extends ViewPager2.OnPageChangeCallback {

    private int currentPage = -1;

    @Override
    public void onPageSelected(int position) {
      if (currentPage != -1 && currentPage != position) onPageUnselected(currentPage);
      currentPage = position;

      if (adapter == null) return;

      MediaItem item = adapter.getMediaItemFor(position);
      if (item.recipient != null) item.recipient.addListener(MediaPreviewActivity.this);
      viewModel.setActiveAlbumRailItem(MediaPreviewActivity.this, position);
      updateActionBar();
    }


    public void onPageUnselected(int position) {
      if (adapter == null) return;

      try {
        MediaItem item = adapter.getMediaItemFor(position);
        if (item.recipient != null) item.recipient.removeListener(MediaPreviewActivity.this);
      } catch (CursorIndexOutOfBoundsException e) {
        throw new RuntimeException("position = " + position + " leftIsRecent = " + leftIsRecent, e);
      }

      adapter.pause(position);
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
      /* Do nothing */
    }

    @Override
    public void onPageScrollStateChanged(int state) { /* Do nothing */ }
  }

  private static class SingleItemPagerAdapter extends MediaItemAdapter {

    private final RequestManager glideRequests;
    private final Window        window;
    private final Uri           uri;
    private final String        mediaType;
    private final long          size;


    SingleItemPagerAdapter(@NonNull RequestManager glideRequests,
                           @NonNull Window window, @NonNull Uri uri, @NonNull String mediaType,
                           long size)
    {
      this.glideRequests = glideRequests;
      this.window        = window;
      this.uri           = uri;
      this.mediaType     = mediaType;
      this.size          = size;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      return new RecyclerView.ViewHolder(
              MediaViewPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false).getRoot()
      ) {};
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
      final MediaViewPageBinding binding = MediaViewPageBinding.bind(holder.itemView);

      try {
        binding.mediaView.set(glideRequests, window, uri, mediaType, size, true);
      } catch (IOException e) {
        Log.w(TAG, e);
      }
    }

    @Override
    public int getItemCount() {
      return 1;
    }

    @Override
    public MediaItem getMediaItemFor(int position) {
      return new MediaItem(null, null, uri, mediaType, -1, true);
    }

    @Override
    public void pause(int position) { /* Do nothing */ }

    @Override
    public @Nullable View getPlaybackControls(int position) {
      return null;
    }
  }

  private static class CursorPagerAdapter extends MediaItemAdapter {

    private final WeakHashMap<Integer, MediaView> mediaViews = new WeakHashMap<>();

    private final Context       context;
    private final RequestManager glideRequests;
    private final Window        window;
    private final Cursor        cursor;
    private final boolean       leftIsRecent;

    private int     autoPlayPosition;

    CursorPagerAdapter(@NonNull Context context, @NonNull RequestManager glideRequests,
                       @NonNull Window window, @NonNull Cursor cursor, int autoPlayPosition,
                       boolean leftIsRecent)
    {
      this.context          = context.getApplicationContext();
      this.glideRequests    = glideRequests;
      this.window           = window;
      this.cursor           = cursor;
      this.autoPlayPosition = autoPlayPosition;
      this.leftIsRecent     = leftIsRecent;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      return new RecyclerView.ViewHolder(MediaViewPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false).getRoot()) {};
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
      final MediaViewPageBinding binding = MediaViewPageBinding.bind(holder.itemView);

      boolean   autoplay       = position == autoPlayPosition;
      int       cursorPosition = getCursorPosition(position);

      autoPlayPosition = -1;

      cursor.moveToPosition(cursorPosition);

      MediaRecord mediaRecord = MediaRecord.from(context, cursor);

      try {
        //noinspection ConstantConditions
        binding.mediaView.set(glideRequests, window, mediaRecord.getAttachment().getDataUri(),
                mediaRecord.getAttachment().getContentType(), mediaRecord.getAttachment().getSize(), autoplay);
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      mediaViews.put(position, binding.mediaView);
    }

    @Override
    public int getItemCount() {
      return cursor.getCount();
    }

    public MediaItem getMediaItemFor(int position) {
      cursor.moveToPosition(getCursorPosition(position));
      MediaRecord mediaRecord = MediaRecord.from(context, cursor);
      Address     address     = mediaRecord.getAddress();

      if (mediaRecord.getAttachment().getDataUri() == null) throw new AssertionError();

      return new MediaItem(address != null ? Recipient.from(context, address,true) : null,
                           mediaRecord.getAttachment(),
                           mediaRecord.getAttachment().getDataUri(),
                           mediaRecord.getContentType(),
                           mediaRecord.getDate(),
                           mediaRecord.isOutgoing());
    }

    @Override
    public void pause(int position) {
      MediaView mediaView = mediaViews.get(position);
      if (mediaView != null) mediaView.pause();
    }

    @Override
    public @Nullable View getPlaybackControls(int position) {
      MediaView mediaView = mediaViews.get(position);
      if (mediaView != null) return mediaView.getPlaybackControls();
      return null;
    }

    private int getCursorPosition(int position) {
        int unclamped = leftIsRecent ? position : cursor.getCount() - 1 - position;
        return Math.max(Math.min(unclamped, cursor.getCount() - 1), 0);
    }
  }

  private static class MediaItem {
    private final @Nullable Recipient          recipient;
    private final @Nullable DatabaseAttachment attachment;
    private final @NonNull  Uri                uri;
    private final @NonNull  String             mimeType;
    private final           long               date;
    private final           boolean            outgoing;

    private MediaItem(@Nullable Recipient recipient,
                      @Nullable DatabaseAttachment attachment,
                      @NonNull Uri uri,
                      @NonNull String mimeType,
                      long date,
                      boolean outgoing)
    {
      this.recipient  = recipient;
      this.attachment = attachment;
      this.uri        = uri;
      this.mimeType   = mimeType;
      this.date       = date;
      this.outgoing   = outgoing;
    }
  }

  abstract static class MediaItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    abstract MediaItem getMediaItemFor(int position);
    abstract void pause(int position);
    @Nullable abstract View getPlaybackControls(int position);
  }
}
