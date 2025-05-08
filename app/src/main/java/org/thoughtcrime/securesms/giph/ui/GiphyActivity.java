package org.thoughtcrime.securesms.giph.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.session.libsession.utilities.MediaTypes;
import org.session.libsession.utilities.NonTranslatableStringConstants;
import org.thoughtcrime.securesms.ScreenLockActionBarActivity;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.session.libsession.utilities.ViewUtil;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import network.loki.messenger.R;
import network.loki.messenger.databinding.GiphyActivityBinding;

public class GiphyActivity extends ScreenLockActionBarActivity
    implements GiphyActivityToolbar.OnLayoutChangedListener,
               GiphyActivityToolbar.OnFilterChangedListener,
               GiphyAdapter.OnItemClickListener
{

  private static final String TAG = GiphyActivity.class.getSimpleName();

  public static final String EXTRA_IS_MMS = "extra_is_mms";
  public static final String EXTRA_WIDTH  = "extra_width";
  public static final String EXTRA_HEIGHT = "extra_height";

  private GiphyGifFragment     gifFragment;
  private GiphyStickerFragment stickerFragment;
  private boolean              forMms;

  private GiphyActivityBinding binding;

  private GiphyAdapter.GiphyViewHolder finishingImage;

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    binding = GiphyActivityBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    initializeToolbar();
    initializeResources();
  }

  private void initializeToolbar() {
    GiphyActivityToolbar toolbar = ViewUtil.findById(this, R.id.giphy_toolbar);
    toolbar.setOnFilterChangedListener(this);
    toolbar.setOnLayoutChangedListener(this);
    toolbar.setPersistence(GiphyActivityToolbarTextSecurePreferencesPersistence.fromContext(this));

    setSupportActionBar(toolbar);

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setHomeButtonEnabled(true);
  }

  private void initializeResources() {
    this.gifFragment     = new GiphyGifFragment();
    this.stickerFragment = new GiphyStickerFragment();
    this.forMms          = getIntent().getBooleanExtra(EXTRA_IS_MMS, false);

    binding.giphyPager.setAdapter(new GiphyFragmentPagerAdapter(this));

    new TabLayoutMediator(binding.tabLayout, binding.giphyPager, (tab, position) -> {
      tab.setText(position == 0 ? NonTranslatableStringConstants.GIF : getString(R.string.stickers));
    }).attach();
  }

  @Override
  public void onFilterChanged(String filter) {
    this.gifFragment.setSearchString(filter);
    this.stickerFragment.setSearchString(filter);
  }

  @Override
  public void onLayoutChanged(boolean gridLayout) {
    gifFragment.setLayoutManager(gridLayout);
    stickerFragment.setLayoutManager(gridLayout);
  }

  @SuppressLint("StaticFieldLeak")
  @Override
  public void onClick(final GiphyAdapter.GiphyViewHolder viewHolder) {
    if (finishingImage != null) finishingImage.gifProgress.setVisibility(View.GONE);
    finishingImage = viewHolder;
    finishingImage.gifProgress.setVisibility(View.VISIBLE);

    new AsyncTask<Void, Void, Uri>() {
      @Override
      protected Uri doInBackground(Void... params) {
        try {
          byte[] data = viewHolder.getData(forMms);

          return BlobProvider.getInstance()
                             .forData(data)
                             .withMimeType(MediaTypes.IMAGE_GIF)
                             .createForSingleSessionOnDisk(GiphyActivity.this, e -> Log.w(TAG, "Failed to write to disk.", e))
                  .get();
        } catch (InterruptedException | ExecutionException | IOException e) {
          Log.w(TAG, e);
          return null;
        }
      }

      protected void onPostExecute(@Nullable Uri uri) {
        if (uri == null) {
          Toast.makeText(GiphyActivity.this, R.string.errorUnknown, Toast.LENGTH_LONG).show();
        } else if (viewHolder == finishingImage) {
          Intent intent = new Intent();
          intent.setData(uri);
          intent.putExtra(EXTRA_WIDTH, viewHolder.image.getGifWidth());
          intent.putExtra(EXTRA_HEIGHT, viewHolder.image.getGifHeight());
          setResult(RESULT_OK, intent);
          finish();
        } else {
          Log.w(TAG, "Resolved Uri is no longer the selected element...");
        }
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private class GiphyFragmentPagerAdapter extends FragmentStateAdapter {

    private GiphyFragmentPagerAdapter(@NonNull FragmentActivity activity)
    {
      super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
      return position == 0 ? gifFragment : stickerFragment;
    }

    @Override
    public int getItemCount() {
      return 2;
    }
  }

}
