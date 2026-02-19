package org.thoughtcrime.securesms.giph.ui;

import android.content.Context;

import org.thoughtcrime.securesms.dependencies.AppComponent;
import dagger.hilt.EntryPoints;
import org.thoughtcrime.securesms.preferences.MessagingPreferences;
import org.thoughtcrime.securesms.preferences.PreferenceStorage;

class GiphyActivityToolbarTextSecurePreferencesPersistence implements GiphyActivityToolbar.Persistence {

  static GiphyActivityToolbar.Persistence fromContext(Context context) {
    return new GiphyActivityToolbarTextSecurePreferencesPersistence(context.getApplicationContext());
  }

  private final PreferenceStorage preferenceStorage;

  private GiphyActivityToolbarTextSecurePreferencesPersistence(Context context) {
    this.preferenceStorage = EntryPoints.get(context, AppComponent.class).getPreferenceStorage();
  }

  @Override
  public boolean getGridSelected() {
    return preferenceStorage.get(MessagingPreferences.INSTANCE.getGIF_SEARCH_IN_GRID_LAYOUT());
  }

  @Override
  public void setGridSelected(boolean isGridSelected) {
    preferenceStorage.set(MessagingPreferences.INSTANCE.getGIF_SEARCH_IN_GRID_LAYOUT(), isGridSelected);
  }
}
