package org.thoughtcrime.securesms.audio;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.session.libsession.utilities.MediaTypes;
import org.session.libsession.utilities.Util;
import org.session.libsignal.utilities.ListenableFuture;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.SettableFuture;
import org.session.libsignal.utilities.ThreadUtils;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.MediaUtil;

public class AudioRecorder {

  private static final String TAG = AudioRecorder.class.getSimpleName();

  private static final ExecutorService executor = ThreadUtils.newDynamicSingleThreadedExecutor();

  private final Context context;

  private AudioCodec audioCodec;
  private Future<Uri> blobWritingTask;

  // Simple interface that allows us to provide a callback method to our `startRecording` method
  public interface AudioMessageRecordingFinishedCallback {
    void onAudioMessageRecordingFinished();
  }

  public AudioRecorder(@NonNull Context context) {
    this.context = context;
  }

  public void startRecording(AudioMessageRecordingFinishedCallback callback) {
    Log.i(TAG, "startRecording()");

    executor.execute(() -> {
      Log.i(TAG, "Running startRecording() + " + Thread.currentThread().getId());
      try {
        if (audioCodec != null) {
          Log.e(TAG, "Trying to start recording while another recording is in progress, exiting...");
          return;
        }

        ParcelFileDescriptor fds[] = ParcelFileDescriptor.createPipe();

        blobWritingTask = BlobProvider.getInstance()
                                 .forData(new ParcelFileDescriptor.AutoCloseInputStream(fds[0]), 0)
                                 .withMimeType(MediaTypes.AUDIO_AAC)
                                 .createForSingleSessionOnDisk(context, e -> Log.w(TAG, "Error during recording", e));

        audioCodec = new AudioCodec();
        audioCodec.start(new ParcelFileDescriptor.AutoCloseOutputStream(fds[1]));

        callback.onAudioMessageRecordingFinished();
      } catch (IOException e) {
        Log.w(TAG, e);
      }
    });
  }

  public @NonNull ListenableFuture<Pair<Uri, Long>> stopRecording(boolean voiceMessageMeetsMinimumDuration) {
    Log.i(TAG, "stopRecording()");

    final SettableFuture<Pair<Uri, Long>> future = new SettableFuture<>();

    executor.execute(() -> {
      if (audioCodec == null || blobWritingTask == null) {
        sendToFuture(future, new IOException("MediaRecorder was never initialized successfully!"));
        return;
      }

      audioCodec.stop();
      try {
        final Uri captureUri = blobWritingTask.get();
        long size = 0L;
        // Only obtain the media size if the voice message was at least our minimum allowed
        // duration (bypassing this work prevents the audio recording mechanism from getting into
        // a broken state should the user rapidly spam the record button for several seconds).
        if (voiceMessageMeetsMinimumDuration) {
          size = MediaUtil.getMediaSize(context, captureUri);
        }
        sendToFuture(future, new Pair<>(captureUri, size));
      } catch (IOException | ExecutionException | InterruptedException e) {
        Log.w(TAG, e);
        sendToFuture(future, e);
      }

      audioCodec = null;
      blobWritingTask = null;
    });

    return future;
  }

  private <T> void sendToFuture(final SettableFuture<T> future, final Exception exception) {
    Util.runOnMain(() -> future.setException(exception));
  }

  private <T> void sendToFuture(final SettableFuture<T> future, final T result) {
    Util.runOnMain(() -> future.set(result));
  }
}
