package org.thoughtcrime.securesms.mediasend;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.provider.OpenableColumns;
import android.util.Pair;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import com.annimon.stream.Stream;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.util.MediaUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import network.loki.messenger.R;

/**
 * Handles the retrieval of media present on the user's device.
 */
class MediaRepository {

    /**
     * Retrieves a list of folders that contain media.
     */
    void getFolders(@NonNull Context context, @NonNull Callback<List<MediaFolder>> callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> callback.onComplete(getFolders(context)));
    }

    /**
     * Retrieves a list of media items (images and videos) that are present int he specified bucket.
     */
    void getMediaInBucket(@NonNull Context context, @NonNull String bucketId, @NonNull Callback<List<Media>> callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> callback.onComplete(getMediaInBucket(context, bucketId)));
    }

    /**
     * Given an existing list of {@link Media}, this will ensure that the media is populate with as
     * much data as we have, like width/height.
     */
    void getPopulatedMedia(@NonNull Context context, @NonNull List<Media> media, @NonNull Callback<List<Media>> callback) {
        if (Stream.of(media).allMatch(this::isPopulated)) {
            callback.onComplete(media);
            return;
        }

        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> callback.onComplete(getPopulatedMedia(context, media)));
    }

    @WorkerThread
    private @NonNull List<MediaFolder> getFolders(@NonNull Context context) {
        FolderResult imageFolders = getFolders(context, Images.Media.EXTERNAL_CONTENT_URI);
        FolderResult videoFolders = getFolders(context, Video.Media.EXTERNAL_CONTENT_URI);

        // Merge image and video folder data
        Map<String, FolderData> mergedFolders = new HashMap<>(imageFolders.getFolderData());
        for (Map.Entry<String, FolderData> entry : videoFolders.getFolderData().entrySet()) {
            if (mergedFolders.containsKey(entry.getKey())) {
                mergedFolders.get(entry.getKey()).incrementCount(entry.getValue().getCount());
                // Also update timestamp if the video has a more recent timestamp.
                mergedFolders.get(entry.getKey()).updateTimestamp(entry.getValue().getLatestTimestamp());
            } else {
                mergedFolders.put(entry.getKey(), entry.getValue());
            }
        }

        // Create a list from merged folder data
        List<FolderData> folderDataList = new ArrayList<>(mergedFolders.values());
        // Sort folders by their latestTimestamp (most recent first)
        Collections.sort(folderDataList, (fd1, fd2) -> Long.compare(fd2.getLatestTimestamp(), fd1.getLatestTimestamp()));

        List<MediaFolder> mediaFolders = new ArrayList<>();
        for (FolderData fd : folderDataList) {
            if (fd.getTitle() != null) {
                mediaFolders.add(new MediaFolder(fd.getThumbnail(), fd.getTitle(), fd.getCount(), fd.getBucketId()));
            }
        }

        // Determine the global thumbnail from the most recent media across image and video queries
        Uri allMediaThumbnail = imageFolders.getThumbnailTimestamp() > videoFolders.getThumbnailTimestamp()
                ? imageFolders.getThumbnail() : videoFolders.getThumbnail();

        if (allMediaThumbnail != null) {
            int allMediaCount = 0;
            for (MediaFolder folder : mediaFolders) {
                allMediaCount += folder.getItemCount();
            }
            // Prepend an "All Media" folder
            mediaFolders.add(0, new MediaFolder(allMediaThumbnail, context.getString(R.string.conversationsSettingsAllMedia), allMediaCount, Media.ALL_MEDIA_BUCKET_ID));
        }

        return mediaFolders;
    }


    @WorkerThread
    private @NonNull FolderResult getFolders(@NonNull Context context, @NonNull Uri contentUri) {
        Uri globalThumbnail = null;
        long thumbnailTimestamp = 0;
        Map<String, FolderData> folders = new HashMap<>();

        String[] projection = new String[] {
                Images.Media._ID,
                Images.Media.BUCKET_ID,
                Images.Media.BUCKET_DISPLAY_NAME,
                Images.Media.DATE_MODIFIED
        };
        
        String selection = null;
        String sortBy = Images.Media.BUCKET_DISPLAY_NAME + " COLLATE NOCASE ASC, " +
                Images.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = context.getContentResolver().query(contentUri, projection, selection, null, sortBy)) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(Images.Media._ID);
                int bucketIdIndex = cursor.getColumnIndexOrThrow(Images.Media.BUCKET_ID);
                int bucketDisplayNameIndex = cursor.getColumnIndexOrThrow(Images.Media.BUCKET_DISPLAY_NAME);
                int dateIndex = cursor.getColumnIndexOrThrow(Images.Media.DATE_MODIFIED);

                while (cursor.moveToNext()) {
                    long rowId = cursor.getLong(idIndex);
                    Uri thumbnail = ContentUris.withAppendedId(contentUri, rowId);
                    String bucketId = cursor.getString(bucketIdIndex);
                    String title = cursor.getString(bucketDisplayNameIndex);
                    long timestamp = cursor.getLong(dateIndex);

                    FolderData folder = folders.get(bucketId);
                    if (folder == null) {
                        folder = new FolderData(thumbnail, title, bucketId);
                        folders.put(bucketId, folder);
                    }
                    folder.incrementCount();
                    folder.updateTimestamp(timestamp);

                    if (timestamp > thumbnailTimestamp) {
                        globalThumbnail = thumbnail;
                        thumbnailTimestamp = timestamp;
                    }
                }
            }
        }

        return new FolderResult(globalThumbnail, thumbnailTimestamp, folders);
    }

    @WorkerThread
    private @NonNull List<Media> getMediaInBucket(@NonNull Context context, @NonNull String bucketId) {
        List<Media> images = getMediaInBucket(context, bucketId, Images.Media.EXTERNAL_CONTENT_URI, true);
        List<Media> videos = getMediaInBucket(context, bucketId, Video.Media.EXTERNAL_CONTENT_URI, false);
        List<Media> media  = new ArrayList<>(images.size() + videos.size());

        media.addAll(images);
        media.addAll(videos);
        Collections.sort(media, (o1, o2) -> Long.compare(o2.getDate(), o1.getDate()));

        return media;
    }

    @WorkerThread
    private @NonNull List<Media> getMediaInBucket(@NonNull Context context, @NonNull String bucketId, @NonNull Uri contentUri, boolean isImage) {
        List<Media> media         = new LinkedList<>();
        String      selection     = Images.Media.BUCKET_ID + " = ?";
        String[]    selectionArgs = new String[] { bucketId};
        String      sortBy        = Images.Media.DATE_MODIFIED + " DESC";

        String[] projection;

        if (isImage) {
            projection = new String[]{Images.Media._ID, Images.Media.MIME_TYPE, Images.Media.DATE_MODIFIED, Images.Media.ORIENTATION, Images.Media.WIDTH, Images.Media.HEIGHT, Images.Media.SIZE, Images.Media.DISPLAY_NAME};
        } else {
            projection = new String[]{Images.Media._ID, Images.Media.MIME_TYPE, Images.Media.DATE_MODIFIED, Images.Media.WIDTH, Images.Media.HEIGHT, Images.Media.SIZE, Images.Media.DISPLAY_NAME};
        }

        if (Media.ALL_MEDIA_BUCKET_ID.equals(bucketId)) {
            selection     = null;
            selectionArgs = null;
        }

        try (Cursor cursor = context.getContentResolver().query(contentUri, projection, selection, selectionArgs, sortBy)) {
            while (cursor != null && cursor.moveToNext()) {
                long   rowId       = cursor.getLong(cursor.getColumnIndexOrThrow(projection[0]));
                Uri    uri         = ContentUris.withAppendedId(contentUri, rowId);
                String mimetype    = cursor.getString(cursor.getColumnIndexOrThrow(Images.Media.MIME_TYPE));
                long   date        = cursor.getLong(cursor.getColumnIndexOrThrow(Images.Media.DATE_MODIFIED));
                int    orientation = isImage ? cursor.getInt(cursor.getColumnIndexOrThrow(Images.Media.ORIENTATION)) : 0;
                int    width       = cursor.getInt(cursor.getColumnIndexOrThrow(getWidthColumn(orientation)));
                int    height      = cursor.getInt(cursor.getColumnIndexOrThrow(getHeightColumn(orientation)));
                long   size        = cursor.getLong(cursor.getColumnIndexOrThrow(Images.Media.SIZE));
                String filename    = cursor.getString(cursor.getColumnIndexOrThrow(Images.Media.DISPLAY_NAME));

                media.add(new Media(uri, filename, mimetype, date, width, height, size, Optional.of(bucketId), Optional.absent()));
            }
        }

        return media;
    }

    @WorkerThread
    private List<Media> getPopulatedMedia(@NonNull Context context, @NonNull List<Media> media) {
        return Stream.of(media).map(m -> {
            try {
                if (isPopulated(m)) {
                    return m;
                } else if (PartAuthority.isLocalUri(m.getUri())) {
                    return getLocallyPopulatedMedia(context, m);
                } else {
                    return getContentResolverPopulatedMedia(context, m);
                }
            } catch (IOException e) {
                return m;
            }
        }).toList();
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private String getWidthColumn(int orientation) {
        if (orientation == 0 || orientation == 180) return Images.Media.WIDTH;
        else                                        return Images.Media.HEIGHT;
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private String getHeightColumn(int orientation) {
        if (orientation == 0 || orientation == 180) return Images.Media.HEIGHT;
        else                                        return Images.Media.WIDTH;
    }

    private boolean isPopulated(@NonNull Media media) {
        return media.getWidth() > 0 && media.getHeight() > 0 && media.getSize() > 0;
    }

    private Media getLocallyPopulatedMedia(@NonNull Context context, @NonNull Media media) throws IOException {
        int  width  = media.getWidth();
        int  height = media.getHeight();
        long size   = media.getSize();

        if (size <= 0) {
            Optional<Long> optionalSize = Optional.fromNullable(PartAuthority.getAttachmentSize(context, media.getUri()));
            size = optionalSize.isPresent() ? optionalSize.get() : 0;
        }

        if (size <= 0) {
            size = MediaUtil.getMediaSize(context, media.getUri());
        }

        if (width == 0 || height == 0) {
            Pair<Integer, Integer> dimens = MediaUtil.getDimensions(context, media.getMimeType(), media.getUri());
            width  = dimens.first;
            height = dimens.second;
        }

        return new Media(media.getUri(), media.getFilename(), media.getMimeType(), media.getDate(), width, height, size, media.getBucketId(), media.getCaption());
    }

    private Media getContentResolverPopulatedMedia(@NonNull Context context, @NonNull Media media) throws IOException {
        int  width  = media.getWidth();
        int  height = media.getHeight();
        long size   = media.getSize();

        if (size <= 0) {
            try (Cursor cursor = context.getContentResolver().query(media.getUri(), null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getColumnIndex(OpenableColumns.SIZE) >= 0) {
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                }
            }
        }

        if (size <= 0) {
            size = MediaUtil.getMediaSize(context, media.getUri());
        }

        if (width == 0 || height == 0) {
            Pair<Integer, Integer> dimens = MediaUtil.getDimensions(context, media.getMimeType(), media.getUri());
            width  = dimens.first;
            height = dimens.second;
        }

        return new Media(media.getUri(), media.getFilename(), media.getMimeType(), media.getDate(), width, height, size, media.getBucketId(), media.getCaption());
    }

    private static class FolderResult {
        private final Uri                     thumbnail;
        private final long                    thumbnailTimestamp;
        private final Map<String, FolderData> folderData;

        private FolderResult(@Nullable Uri thumbnail,
                             long thumbnailTimestamp,
                             @NonNull Map<String, FolderData> folderData)
        {
            this.thumbnail          = thumbnail;
            this.thumbnailTimestamp = thumbnailTimestamp;
            this.folderData         = folderData;
        }

        @Nullable Uri getThumbnail() {
            return thumbnail;
        }

        long getThumbnailTimestamp() {
            return thumbnailTimestamp;
        }

        @NonNull Map<String, FolderData> getFolderData() {
            return folderData;
        }
    }

    private static class FolderData {
        private final Uri thumbnail;
        private final String title;
        private final String bucketId;
        private int count;
        private long latestTimestamp; // New field

        private FolderData(@NonNull Uri thumbnail, @NonNull String title, @NonNull String bucketId) {
            this.thumbnail = thumbnail;
            this.title = title;
            this.bucketId = bucketId;
            this.count = 0;
            this.latestTimestamp = 0;
        }

        Uri getThumbnail() {
            return thumbnail;
        }

        String getTitle() {
            return title;
        }

        String getBucketId() {
            return bucketId;
        }

        int getCount() {
            return count;
        }

        void incrementCount() {
            incrementCount(1);
        }

        void incrementCount(int amount) {
            count += amount;
        }

        void updateTimestamp(long ts) {
            if (ts > latestTimestamp) {
                latestTimestamp = ts;
            }
        }

        long getLatestTimestamp() {
            return latestTimestamp;
        }
    }


    interface Callback<E> {
        void onComplete(@NonNull E result);
    }
}
