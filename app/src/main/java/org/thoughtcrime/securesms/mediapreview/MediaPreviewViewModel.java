package org.thoughtcrime.securesms.mediapreview;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.database.MediaDatabase.MediaRecord;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.util.FilenameUtils;

@HiltViewModel
public class MediaPreviewViewModel extends ViewModel {

    private final Context context;

    @Inject
    public MediaPreviewViewModel(@ApplicationContext Context context) { this.context = context; }

    private final MutableLiveData<PreviewData> previewData = new MutableLiveData<>();

    private boolean leftIsRecent;

    private @Nullable Cursor cursor;

    //  map of playback position of the pager's items
    private final Map<Uri, Long> playbackPositions = new HashMap<>();

    public void setCursor(@NonNull Context context, @Nullable Cursor cursor, boolean leftIsRecent) {
        boolean firstLoad = (this.cursor == null) && (cursor != null);
        if (this.cursor != null && !this.cursor.equals(cursor)) {
            this.cursor.close();
        }
        this.cursor       = cursor;
        this.leftIsRecent = leftIsRecent;

        if (firstLoad) {
            setActiveAlbumRailItem(context, 0);
        }
    }

    public void savePlaybackPosition(Uri videoUri, long position) {
        playbackPositions.put(videoUri, position);
    }

    public long getSavedPlaybackPosition(Uri videoUri) {
        Long position = playbackPositions.get(videoUri);
        return position != null ? position : 0L;
    }

    public void setActiveAlbumRailItem(@NonNull Context context, int activePosition) {
        if (cursor == null) {
            previewData.postValue(new PreviewData(Collections.emptyList(), null, 0));
            return;
        }

        activePosition = getCursorPosition(activePosition);

        cursor.moveToPosition(activePosition);

        MediaRecord       activeRecord = MediaRecord.from(context, cursor);
        LinkedList<Media> rail         = new LinkedList<>();

        Media activeMedia = toMedia(activeRecord);
        if (activeMedia != null) rail.add(activeMedia);

        while (cursor.moveToPrevious()) {
            MediaRecord record = MediaRecord.from(context, cursor);
            if (record.getAttachment().getMmsId() == activeRecord.getAttachment().getMmsId()) {
                Media media = toMedia(record);
                if (media != null) rail.addFirst(media);
            } else {
                break;
            }
        }

        cursor.moveToPosition(activePosition);

        while (cursor.moveToNext()) {
            MediaRecord record = MediaRecord.from(context, cursor);
            if (record.getAttachment().getMmsId() == activeRecord.getAttachment().getMmsId()) {
                Media media = toMedia(record);
                if (media != null) rail.addLast(media);
            } else {
                break;
            }
        }

        if (!leftIsRecent) {
            Collections.reverse(rail);
        }

        previewData.postValue(new PreviewData(rail.size() > 1 ? rail : Collections.emptyList(),
                activeRecord.getAttachment().getCaption(),
                rail.indexOf(activeMedia)));
    }

    private int getCursorPosition(int position) {
        if (cursor == null) { return 0;  }
        if (leftIsRecent) return position;
        else              return cursor.getCount() - 1 - position;
    }

    private @Nullable Media toMedia(@NonNull MediaRecord mediaRecord) {
        Uri uri = mediaRecord.getAttachment().getThumbnailUri() != null ? mediaRecord.getAttachment().getThumbnailUri()
                : mediaRecord.getAttachment().getDataUri();

        if (uri == null) {
            Log.w("MediaPreviewViewModel", "MediaPreviewViewModel cannot construct Media from a null Uri - bailing.");
            return null;
        }

        String filename = mediaRecord.getAttachment().getFilename();
        if (filename == null || filename.isEmpty()) { filename = FilenameUtils.getFilenameFromUri(context, uri); }

        return new Media(uri,
                filename,
                mediaRecord.getContentType(),
                mediaRecord.getDate(),
                mediaRecord.getAttachment().getWidth(),
                mediaRecord.getAttachment().getHeight(),
                mediaRecord.getAttachment().getSize(),
                null,
                mediaRecord.getAttachment().getCaption()
        );
    }

    public LiveData<PreviewData> getPreviewData() {
        return previewData;
    }

    public static class PreviewData {
        private final List<Media> albumThumbnails;
        private final String      caption;
        private final int         activePosition;

        public PreviewData(@NonNull List<Media> albumThumbnails, @Nullable String caption, int activePosition) {
            this.albumThumbnails = albumThumbnails;
            this.caption         = caption;
            this.activePosition  = activePosition;
        }

        public @NonNull List<Media> getAlbumThumbnails() {
            return albumThumbnails;
        }

        public @Nullable String getCaption() {
            return caption;
        }

        public int getActivePosition() {
            return activePosition;
        }
    }
}
