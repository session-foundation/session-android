package org.thoughtcrime.securesms.giph.ui;

import android.os.AsyncTask;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.thoughtcrime.securesms.giph.model.GiphyImage;
import org.thoughtcrime.securesms.giph.net.GiphyLoader;
import org.thoughtcrime.securesms.giph.util.InfiniteScrollListener;
import com.bumptech.glide.Glide;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.ViewUtil;

import java.util.LinkedList;
import java.util.List;

import network.loki.messenger.R;

public abstract class GiphyFragment extends Fragment implements LoaderManager.LoaderCallbacks<List<GiphyImage>>, GiphyAdapter.OnItemClickListener {

  private static final String TAG = GiphyFragment.class.getSimpleName();

  private GiphyAdapter                     giphyAdapter;
  private RecyclerView                     recyclerView;
  private View                             loadingProgress;
  private TextView                         noResultsView;

  protected String searchString;
  private Boolean pendingGridLayout = null;

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup viewGroup, Bundle bundle) {
    ViewGroup container = ViewUtil.inflate(inflater, viewGroup, R.layout.giphy_fragment);
    this.recyclerView    = ViewUtil.findById(container, R.id.giphy_list);
    this.loadingProgress = ViewUtil.findById(container, R.id.loading_progress);
    this.noResultsView   = ViewUtil.findById(container, R.id.no_results);

    // Now that views are ready, apply the searchString if it's set
    applySearchStringToUI();

    // Apply pending layout if it was set before view was ready
    if (pendingGridLayout != null) {
      setLayoutManager(pendingGridLayout);
      pendingGridLayout = null;
    } else {
      // Or set default
      setLayoutManager(TextSecurePreferences.isGifSearchInGridLayout(getContext()));
    }

    return container;
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    this.giphyAdapter = new GiphyAdapter(getActivity(), Glide.with(this), new LinkedList<>());
    this.giphyAdapter.setListener(this);

    this.recyclerView.setItemAnimator(new DefaultItemAnimator());
    this.recyclerView.setAdapter(giphyAdapter);
    this.recyclerView.addOnScrollListener(new GiphyScrollListener());

    getLoaderManager().initLoader(0, null, this);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<List<GiphyImage>> loader, @NonNull List<GiphyImage> data) {
    this.loadingProgress.setVisibility(View.GONE);

    if (data.isEmpty()) noResultsView.setVisibility(View.VISIBLE);
    else                noResultsView.setVisibility(View.GONE);

    this.giphyAdapter.setImages(data);
  }

  @Override
  public void onLoaderReset(@NonNull Loader<List<GiphyImage>> loader) {
    noResultsView.setVisibility(View.GONE);
    this.giphyAdapter.setImages(new LinkedList<GiphyImage>());
  }

  public void setLayoutManager(boolean gridLayout) {
    if (recyclerView != null) {
      recyclerView.setLayoutManager(getLayoutManager(gridLayout));
    } else {
      pendingGridLayout = gridLayout;
    }
  }

  private RecyclerView.LayoutManager getLayoutManager(boolean gridLayout) {
    return gridLayout ? new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                      : new LinearLayoutManager(getActivity());
  }


  public void setSearchString(@Nullable String searchString) {
    this.searchString = searchString;
    if (this.noResultsView != null) {
      applySearchStringToUI();
    }
  }

  private void applySearchStringToUI() {
    if (this.noResultsView != null) {
      this.noResultsView.setVisibility(View.GONE);
      this.getLoaderManager().restartLoader(0, null, this);
    }
  }

  @Override
  public void onClick(GiphyAdapter.GiphyViewHolder viewHolder) {
    if (getActivity() instanceof GiphyAdapter.OnItemClickListener) {
      ((GiphyAdapter.OnItemClickListener) getActivity()).onClick(viewHolder);
    }
  }

  private class GiphyScrollListener extends InfiniteScrollListener {
    @Override
    public void onLoadMore(final int currentPage) {
      final Loader<List<GiphyImage>> loader = getLoaderManager().getLoader(0);
      if (loader == null) return;

      new AsyncTask<Void, Void, List<GiphyImage>>() {
        @Override
        protected List<GiphyImage> doInBackground(Void... params) {
          return ((GiphyLoader)loader).loadPage(currentPage * GiphyLoader.PAGE_SIZE);
        }

        protected void onPostExecute(List<GiphyImage> images) {
          giphyAdapter.addImages(images);
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }
}
