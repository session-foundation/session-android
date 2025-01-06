/*
 * Copyright (C) 2014-2017 Open Whisper Systems
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
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcel;
import android.provider.OpenableColumns;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.squareup.phrase.Phrase;
import dagger.hilt.android.AndroidEntryPoint;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import network.loki.messenger.R;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.DistributionTypes;
import org.session.libsession.utilities.ViewUtil;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.components.SearchToolbar;
import org.thoughtcrime.securesms.contacts.ContactSelectionListFragment;
import org.thoughtcrime.securesms.contacts.ContactSelectionListLoader.DisplayMode;
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.MediaUtil;

/**
 * An activity to quickly share content with contacts
 *
 * @author Jake McGinty
 */
@AndroidEntryPoint
public class ShareActivity extends PassphraseRequiredActionBarActivity
        implements ContactSelectionListFragment.OnContactSelectedListener {
    private static final String TAG = "ACL"; //ShareActivity.class.getSimpleName();

    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_ADDRESS_MARSHALLED = "address_marshalled";
    public static final String EXTRA_DISTRIBUTION_TYPE = "distribution_type";

    private ContactSelectionListFragment contactsFragment;
    private SearchToolbar searchToolbar;
    private ImageView searchAction;
    private View progressWheel;
    private Uri resolvedExtra;
    private CharSequence resolvedPlaintext;
    private String mimeType;
    private boolean isPassingAlongMedia;

    private ResolveMediaTask resolveTask;

    @Override
    protected void onCreate(Bundle icicle, boolean ready) {
        Log.i(TAG, "Hit ShareActivity.onCreate()");

        setContentView(R.layout.share_activity);

        Intent i = getIntent();
        Bundle b = i.getExtras();
        if (b != null) {
            for (String key : b.keySet()) {
                Log.w(TAG, "ShareActivity >> Key: " + key + " --> " + b.get(key));
            }
        } else {
            Log.i(TAG, "Bundle was null in ShareActivity");
        }

        if (!i.hasExtra(ContactSelectionListFragment.DISPLAY_MODE)) {
            i.putExtra(ContactSelectionListFragment.DISPLAY_MODE, DisplayMode.FLAG_ALL);
        }

        i.putExtra(ContactSelectionListFragment.REFRESHABLE, false);

        // Are we sharing something or just unlocking the device?
//        boolean intentIsRegardingExternalSharing = IsIntentRegardingExternalSharing(i);
//
//        if (intentIsRegardingExternalSharing) {
//            try {
//                Log.w("ACL", "Found that this intent is regarding external sharing!");
//                if (intentIsRegardingExternalSharing) {
//                    // Clear our list of created files
//                    createdFiles.clear()
//
//                    // Attempt to rewrite any URIs from clipData into our own FileProvider (this also populates the createdFiles list)
//                    val rewrittenIntent = rewriteShareIntentUris(nextIntent!!)
//
//                    if (rewrittenIntent != null) {
//                        // Get the file paths of all created files and add them to our intent then start the activity
//                        val createdFilePaths = createdFiles.map { it.absolutePath }
//                        rewrittenIntent.putStringArrayListExtra("cached_file_paths", ArrayList(createdFilePaths))
//                        startActivity(rewrittenIntent)
//                    } else {
//                        // Moan and bail.
//                        // Note: We'll hit the `finish()` call on the last line from here so no need to call it specifically
//                        Log.e(TAG, "Cannot use null rewrittenIntent for external sharing - bailing.")
//                    }
//                } else {
//                    startActivity(nextIntent)
//                }
//            } catch (SecurityException se) {
//                Log.w(TAG, "Access permission not passed from PassphraseActivity, retry sharing.", se);
//            }
//        }


        // Ensure that the Intent has permission to read any URI (e.g., add it via a bitwise OR op.)
        //int intentFlags = i.getFlags();
        //i.setFlags(intentFlags | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        initializeToolbar();
        initializeResources();
        initializeSearch();
        initializeMedia();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(TAG, "Hit ShareActivity.onNewIntent()");

        // Ensure that the Intent has permission to read any URI (e.g., add it via a bitwise OR op.)
        //int intentFlags = intent.getFlags();
        //intent.setFlags(intentFlags | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        super.onNewIntent(intent);
        setIntent(intent);
        initializeMedia();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!isPassingAlongMedia && resolvedExtra != null) {
            BlobProvider.getInstance().delete(this, resolvedExtra);

            if (!isFinishing()) { finish(); }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (searchToolbar.isVisible()) searchToolbar.collapse();
        else super.onBackPressed();
    }

    private void initializeToolbar() {
        TextView toolbarTitle = findViewById(R.id.title);
        toolbarTitle.setText(
                Phrase.from(getApplicationContext(), R.string.shareToSession)
                        .put(APP_NAME_KEY, getString(R.string.app_name))
                        .format().toString()
        );
    }

    // Method to determine if a given Intent is regarding external sharing (true) or not (false)
    private boolean IsIntentRegardingExternalSharing(Intent i) {
        Log.w("ACL", "Checking if this intent is regarding external sharing!");


        // We'll assume the Intent is regarding external sharing for now and change that if we find out otherwise
        boolean intentIsRegardingExternalSharing = true;
        Bundle bundle = i.getExtras();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);

                // If this is just a standard fingerprint unlock and not sharing anything then set our flag accordingly
                if (value instanceof Intent) {
                    String action = ((Intent)value).getAction();
                    if (action != null && action.equals("android.intent.action.MAIN")) {
                        intentIsRegardingExternalSharing = false;
                        break;
                    }
                }
            }
        }
        return intentIsRegardingExternalSharing;
    }

    private void initializeResources() {
        progressWheel = findViewById(R.id.progress_wheel);
        searchToolbar = findViewById(R.id.search_toolbar);
        searchAction = findViewById(R.id.search_action);
        contactsFragment = (ContactSelectionListFragment) getSupportFragmentManager().findFragmentById(R.id.contact_selection_list_fragment);
        contactsFragment.setOnContactSelectedListener(this);
    }

    private void initializeSearch() {
        searchAction.setOnClickListener(v -> searchToolbar.display(searchAction.getX() + (searchAction.getWidth() / 2),
                searchAction.getY() + (searchAction.getHeight() / 2)));

        searchToolbar.setListener(new SearchToolbar.SearchListener() {
            @Override
            public void onSearchTextChange(String text) {
                if (contactsFragment != null) {
                    contactsFragment.setQueryFilter(text);
                }
            }

            @Override
            public void onSearchClosed() {
                if (contactsFragment != null) {
                    contactsFragment.resetQueryFilter();
                }
            }
        });
    }

    private void initializeMedia() {
        final Context context = this;
        isPassingAlongMedia = false;

        Uri streamExtra = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        CharSequence charSequenceExtra = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        mimeType = getMimeType(streamExtra);

        if (streamExtra != null && PartAuthority.isLocalUri(streamExtra)) {
            isPassingAlongMedia = true;
            resolvedExtra = streamExtra;
            handleResolvedMedia(getIntent(), false);
        } else if (charSequenceExtra != null && mimeType != null && mimeType.startsWith("text/")) {
            resolvedPlaintext = charSequenceExtra;
            handleResolvedMedia(getIntent(), false);
        } else {
            if (contactsFragment != null && contactsFragment.getView() != null) {
                contactsFragment.getView().setVisibility(View.GONE);
            }
            progressWheel.setVisibility(View.VISIBLE);
            resolveTask = new ResolveMediaTask(context);
            resolveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, streamExtra);
        }
    }

    private void handleResolvedMedia(Intent intent, boolean animate) {
        long threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
        int distributionType = intent.getIntExtra(EXTRA_DISTRIBUTION_TYPE, -1);
        Address address = null;

        if (intent.hasExtra(EXTRA_ADDRESS_MARSHALLED)) {
            Parcel parcel = Parcel.obtain();
            byte[] marshalled = intent.getByteArrayExtra(EXTRA_ADDRESS_MARSHALLED);
            parcel.unmarshall(marshalled, 0, marshalled.length);
            parcel.setDataPosition(0);
            address = parcel.readParcelable(getClassLoader());
            parcel.recycle();
        }

        boolean hasResolvedDestination = threadId != -1 && address != null && distributionType != -1;

        if (!hasResolvedDestination && animate) {
            ViewUtil.fadeIn(contactsFragment.getView(), 300);
            ViewUtil.fadeOut(progressWheel, 300);
        } else if (!hasResolvedDestination) {
            contactsFragment.getView().setVisibility(View.VISIBLE);
            progressWheel.setVisibility(View.GONE);
        } else {
            createConversation(threadId, address, distributionType);
        }
    }

    private void createConversation(long threadId, Address address, int distributionType) {
        final Intent intent = getBaseShareIntent(ConversationActivityV2.class);
        intent.putExtra(ConversationActivityV2.ADDRESS, address);
        intent.putExtra(ConversationActivityV2.THREAD_ID, threadId);

        isPassingAlongMedia = true;
        startActivity(intent);
    }

    private Intent getBaseShareIntent(final @NonNull Class<?> target) {
        final Intent intent = new Intent(this, target);

        if (resolvedExtra != null) {
            intent.setDataAndType(resolvedExtra, mimeType);
        } else if (resolvedPlaintext != null) {
            intent.putExtra(Intent.EXTRA_TEXT, resolvedPlaintext);
            intent.setType("text/plain");
        }

        return intent;
    }

    private String getMimeType(@Nullable Uri uri) {
        if (uri != null) {
            final String mimeType = MediaUtil.getMimeType(getApplicationContext(), uri);
            if (mimeType != null) return mimeType;
        }
        return MediaUtil.getCorrectedMimeType(getIntent().getType());
    }

    @Override
    public void onContactSelected(String number) {
        Recipient recipient = Recipient.from(this, Address.fromExternal(this, number), true);
        long existingThread = DatabaseComponent.get(this).threadDatabase().getThreadIdIfExistsFor(recipient);
        createConversation(existingThread, recipient.getAddress(), DistributionTypes.DEFAULT);
    }

    @Override
    public void onContactDeselected(String number) { /* Nothing */ }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (resolveTask != null) resolveTask.cancel(true);

        // NO NO NO NO NO --- this is way too early
        // TODO: Figure out when to call the below
        //PassphraseRequiredActionBarActivity.Companion.cleanupCreatedFiles();

        // TODO: Clean up our local cache now, I guess? We'll likely need to keep track of the intent we were given for this
//        Intent i = getIntent();
//        if (i != null) {
//            ArrayList<String> cachedFilePaths = i.getStringArrayListExtra("cached_file_paths");
//            if (cachedFilePaths != null) {
//                Log.i("ACL", "Cleaning up!");
//                for (String filePath : cachedFilePaths) {
//                    File file = new File(filePath);
//                    if (file.exists()) {
//                        boolean deletionSuccessful = file.delete();
//                        if (!deletionSuccessful) {
//                            Log.w("ACL", "Failed to delete cached file: " + filePath);
//                        } else {
//                            Log.i("ACL", "Deleted cached file: " + filePath);
//                        }
//                    }
//                }
//            } else {
//                Log.w("ACL", "Nothing to clean up!");
//            }
//        } else {
//            Log.w("ACL", "Intent was null in ShareActivity.onDestroy!");
//        }
    }

    @SuppressLint("StaticFieldLeak")
    private class ResolveMediaTask extends AsyncTask<Uri, Void, Uri> {
        private final Context context;

        ResolveMediaTask(Context context) {
            this.context = context;
        }

        @Override
        protected Uri doInBackground(Uri... uris) {
            try {
                if (uris.length != 1 || uris[0] == null) {
                    Log.w(TAG, "Invalid URI passed to ResolveMediaTask - bailing.");
                    return null;
                }

                InputStream inputStream;
                if ("file".equals(uris[0].getScheme())) {
                    inputStream = new FileInputStream(uris[0].getPath());
                } else {
                    inputStream = context.getContentResolver().openInputStream(uris[0]); // <-- This is line 380
                }

                if (inputStream == null) {
                    Log.w(TAG, "Failed to create input stream during ShareActivity - bailing.");
                    return null;
                }

                Cursor cursor = getContentResolver().query(uris[0], new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
                String fileName = null;
                Long fileSize = null;

                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        try {
                            fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                            fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, e);
                        }
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }

                return BlobProvider.getInstance()
                        .forData(inputStream, fileSize == null ? 0 : fileSize)
                        .withMimeType(mimeType)
                        .withFileName(fileName)
                        .createForMultipleSessionsOnDisk(context, e -> Log.w(TAG, "Failed to write to disk.", e));
            } catch (IOException ioe) {
                Log.w(TAG, ioe);
                return null;
            }
        }

        @Override
        protected void onPostExecute(Uri uri) {
            resolvedExtra = uri;
            handleResolvedMedia(getIntent(), true);
        }
    }
}