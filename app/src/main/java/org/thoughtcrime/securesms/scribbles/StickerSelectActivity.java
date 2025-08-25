/**
 * Copyright (C) 2016 Open Whisper Systems
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
package org.thoughtcrime.securesms.scribbles;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.tabs.TabLayoutMediator;

import network.loki.messenger.R;
import network.loki.messenger.databinding.ScribbleSelectStickerActivityBinding;

public class StickerSelectActivity extends FragmentActivity implements StickerSelectFragment.StickerSelectionListener {

  public static final String EXTRA_STICKER_FILE = "extra_sticker_file";

  private static final int[] TAB_TITLES = new int[] {
      R.drawable.ic_emoji_custom,
      R.drawable.ic_briefcase,
      R.drawable.ic_paw_print,
      R.drawable.ic_utensils_crossed,
      R.drawable.ic_sun
  };

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final ScribbleSelectStickerActivityBinding binding = ScribbleSelectStickerActivityBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    binding.cameraStickerPager.setAdapter(new StickerPagerAdapter(this, this));

    new TabLayoutMediator(
            binding.cameraStickerTabs,
            binding.cameraStickerPager,
            (tab, position) -> {
                tab.setIcon(TAB_TITLES[position]);
            }
    ).attach();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onStickerSelected(String name) {
    Intent intent = new Intent();
    intent.putExtra(EXTRA_STICKER_FILE, name);
    setResult(RESULT_OK, intent);
    finish();
  }

  static class StickerPagerAdapter extends FragmentStateAdapter {

    private final Fragment[] fragments;

    StickerPagerAdapter(FragmentActivity activity, StickerSelectFragment.StickerSelectionListener listener) {
      super(activity);

      this.fragments = new Fragment[] {
          StickerSelectFragment.newInstance("stickers/emoticons"),
          StickerSelectFragment.newInstance("stickers/clothes"),
          StickerSelectFragment.newInstance("stickers/animals"),
          StickerSelectFragment.newInstance("stickers/food"),
          StickerSelectFragment.newInstance("stickers/weather"),
          };

      for (Fragment fragment : fragments) {
        ((StickerSelectFragment)fragment).setListener(listener);
      }
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
      return fragments[position];
    }

    @Override
    public int getItemCount() {
      return fragments.length;
    }
  }
}
