package org.thoughtcrime.securesms.components;

import android.animation.Animator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.EditText;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;

import org.thoughtcrime.securesms.util.AnimationCompleteListener;

import network.loki.messenger.R;

public class SearchToolbar extends Toolbar {

  private float x, y;
  private MenuItem searchItem;
  private SearchListener listener;

  public SearchToolbar(Context context) {
    super(context);
    initialize();
  }

  public SearchToolbar(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public SearchToolbar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  private void initialize() {
    setNavigationIcon(getContext().getResources().getDrawable(R.drawable.ic_x));
    inflateMenu(R.menu.conversation_list_search);

    this.searchItem = getMenu().findItem(R.id.action_filter_search);
    SearchView searchView = (SearchView) searchItem.getActionView();
    EditText   searchText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);

    searchView.setSubmitButtonEnabled(false);

    if (searchText != null) searchText.setHint(R.string.search);
    else                    searchView.setQueryHint(getResources().getString(R.string.search));

    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
      @Override
      public boolean onQueryTextSubmit(String query) {
        if (listener != null) listener.onSearchTextChange(query);
        return true;
      }

      @Override
      public boolean onQueryTextChange(String newText) { return onQueryTextSubmit(newText); }
    });

    searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
      @Override
      public boolean onMenuItemActionExpand(MenuItem item) {
        return true;
      }

      @Override
      public boolean onMenuItemActionCollapse(MenuItem item) {
        hide();
        return true;
      }
    });

    setNavigationOnClickListener(v -> hide());
  }

  @MainThread
  public void display(float x, float y) {
    if (getVisibility() != View.VISIBLE) {
      this.x = x;
      this.y = y;

      searchItem.expandActionView();

      Animator animator = ViewAnimationUtils.createCircularReveal(this, (int)x, (int)y, 0, getWidth());
      animator.setDuration(400);

      setVisibility(View.VISIBLE);
      animator.start();
    }
  }

  public void collapse() {
    searchItem.collapseActionView();
  }

  @MainThread
  private void hide() {
    if (getVisibility() == View.VISIBLE) {

      if (listener != null) listener.onSearchClosed();

      Animator animator = ViewAnimationUtils.createCircularReveal(this, (int)x, (int)y, getWidth(), 0);
      animator.setDuration(400);
      animator.addListener(new AnimationCompleteListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
          setVisibility(View.INVISIBLE);
        }
      });
      animator.start();
    }
  }

  public boolean isVisible() {
    return getVisibility() == View.VISIBLE;
  }

  @MainThread
  public void setListener(SearchListener listener) {
    this.listener = listener;
  }

  public interface SearchListener {
    void onSearchTextChange(String text);
    void onSearchClosed();
  }

}
