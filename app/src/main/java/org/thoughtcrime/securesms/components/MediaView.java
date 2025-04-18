package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.RequestManager;
import java.io.IOException;
import network.loki.messenger.R;
import org.session.libsession.utilities.Stub;
import org.thoughtcrime.securesms.mms.VideoSlide;
import org.thoughtcrime.securesms.util.FilenameUtils;
import org.thoughtcrime.securesms.video.VideoPlayer;

public class MediaView extends FrameLayout {

  private ZoomingImageView  imageView;
  private Stub<VideoPlayer> videoView;

  public MediaView(@NonNull Context context) {
    super(context);
    initialize();
  }

  public MediaView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public MediaView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  private void initialize() {
    inflate(getContext(), R.layout.media_view, this);

    this.imageView = findViewById(R.id.image);
    this.videoView = new Stub<>(findViewById(R.id.video_player_stub));
  }

  public void set(@NonNull RequestManager glideRequests,
                  @NonNull Window window,
                  @NonNull Uri sourceUri,
                  @NonNull String mediaType,
                  long size,
                  boolean autoplay)
      throws IOException
  {
    if (mediaType.startsWith("image/")) {
      imageView.setVisibility(View.VISIBLE);
      if (videoView.resolved()) videoView.get().setVisibility(View.GONE);
      imageView.setImageUri(glideRequests, sourceUri, mediaType);
    } else if (mediaType.startsWith("video/")) {
      imageView.setVisibility(View.GONE);
      videoView.get().setVisibility(View.VISIBLE);
      videoView.get().setWindow(window);

      Context context = getContext();
      String filename = FilenameUtils.getFilenameFromUri(context, sourceUri);

      videoView.get().setVideoSource(new VideoSlide(context, sourceUri, filename, size), autoplay);
    } else {
      throw new IOException("Unsupported media type: " + mediaType);
    }
  }

  public void pause() {
    if (this.videoView.resolved()){
      this.videoView.get().pause();
    }
  }

  public void hideControls() {
    if (this.videoView.resolved()){
      this.videoView.get().hideControls();
    }
  }

  public @Nullable View getPlaybackControls() {
    if (this.videoView.resolved()){
      return this.videoView.get().getControlView();
    }
    return null;
  }

  public void cleanup() {
    this.imageView.cleanup();
    if (this.videoView.resolved()) {
      this.videoView.get().cleanup();
    }
  }
}
