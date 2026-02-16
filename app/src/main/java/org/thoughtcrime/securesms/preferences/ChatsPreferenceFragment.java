package org.thoughtcrime.securesms.preferences;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import org.thoughtcrime.securesms.permissions.Permissions;

import network.loki.messenger.R;

public class ChatsPreferenceFragment extends CorrectedPreferenceFragment {
  private static final String TAG = ChatsPreferenceFragment.class.getSimpleName();

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_chats);

    Preference blockedContactsPreference = findPreference("blocked_contacts");
    if (blockedContactsPreference != null) {
      blockedContactsPreference.setOnPreferenceClickListener(preference -> {
        startActivity(new Intent(requireContext(), BlockedContactsActivity.class));
        return true;
      });
    }
  }

  @Override
  public void onResume() {
    super.onResume();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

}
