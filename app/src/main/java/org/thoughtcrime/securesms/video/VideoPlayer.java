/*
 * Copyright (C) 2017 Whisper Systems
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
package org.thoughtcrime.securesms.video;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.session.libsession.utilities.ViewUtil;
import org.thoughtcrime.securesms.mms.VideoSlide;

import java.io.IOException;

import network.loki.messenger.R;

public class VideoPlayer extends FrameLayout {

  private static final String TAG = VideoPlayer.class.getSimpleName();

  @Nullable private final PlayerView exoView;

  @Nullable private ExoPlayer exoPlayer;
  @Nullable private       Window              window;

  public interface VideoPlayerInteractions {
    void onControllerVisibilityChanged(boolean visible);
  }

  @Nullable
  private VideoPlayerInteractions interactor = null;

  public VideoPlayer(Context context) {
    this(context, null);
  }

  public VideoPlayer(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

    @OptIn(markerClass = UnstableApi.class)
    public VideoPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    inflate(context, R.layout.video_player, this);

    this.exoView   = ViewUtil.findById(this, R.id.video_view);
    exoView.setControllerShowTimeoutMs(3000);

    TypedValue tv = new TypedValue();
    getContext().getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
    int bgColor = tv.data;
    findViewById(R.id.custom_controls).setBackgroundColor(
            ColorUtils.setAlphaComponent(bgColor, (int) (0.7f * 255))
    );

      // listen to changes in the controller visibility
      exoView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
        @Override
        public void onVisibilityChanged(int visibility) {
          if (interactor != null) interactor.onControllerVisibilityChanged(visibility == View.VISIBLE);
        }
      });
  }

  public void setInteractor(@Nullable VideoPlayerInteractions interactor) {
    this.interactor = interactor;
  }

  public void setVideoSource(@NonNull VideoSlide videoSource, boolean autoplay)
      throws IOException
  {
    setExoViewSource(videoSource, autoplay);
  }

  public void setControlsYPosition(int yPosition){
    org.thoughtcrime.securesms.conversation.v2.ViewUtil.setBottomMargin(findViewById(R.id.custom_controls), yPosition);
  }

  public Long pause() {
    if (this.exoPlayer != null) {
      this.exoPlayer.setPlayWhenReady(false);
      // return last playback position
      return exoPlayer.getCurrentPosition();
    }

    return 0L;
  }

  public void seek(Long position){
    if (exoPlayer != null) exoPlayer.seekTo(position);
  }

  public void cleanup() {
    if (this.exoPlayer != null) {
      this.exoPlayer.release();
    }
  }

  public void setWindow(@Nullable Window window) {
    this.window = window;
  }

    @OptIn(markerClass = UnstableApi.class)
    private void setExoViewSource(@NonNull VideoSlide videoSource, boolean autoplay)
      throws IOException
  {
    exoPlayer = new ExoPlayer.Builder(getContext()).build();
    exoPlayer.addListener(new ExoPlayerListener(window));
    exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true);
    //noinspection ConstantConditions
    exoView.setPlayer(exoPlayer); //todo this should be optimised as it creates a small lag in the viewpager
    //noinspection ConstantConditions

    if(videoSource.getUri() != null){
      MediaItem mediaItem = MediaItem.fromUri(videoSource.getUri());
      exoPlayer.setMediaItem(mediaItem);
    }

    exoPlayer.prepare();
    exoPlayer.setPlayWhenReady(autoplay);
  }

  @UnstableApi
  private static class ExoPlayerListener implements Player.Listener {
    private final Window window;

    ExoPlayerListener(Window window) {
      this.window = window;
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      switch(playbackState) {
        case Player.STATE_IDLE:
        case Player.STATE_BUFFERING:
        case Player.STATE_ENDED:
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          break;
        case Player.STATE_READY:
          if (playWhenReady) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          }
          break;
        default:
          break;
      }
    }
  }
}
