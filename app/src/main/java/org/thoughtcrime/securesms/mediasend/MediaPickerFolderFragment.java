package org.thoughtcrime.securesms.mediasend;

import static org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.squareup.phrase.Phrase;

import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.RecipientNamesKt;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.conversation.v2.utilities.AttachmentManager;
import org.thoughtcrime.securesms.util.ViewUtilitiesKt;

import dagger.hilt.android.AndroidEntryPoint;
import network.loki.messenger.R;

/**
 * Allows the user to select a media folder to explore.
 */
@AndroidEntryPoint
public class MediaPickerFolderFragment extends Fragment implements MediaPickerFolderAdapter.EventListener {

  private static final String KEY_RECIPIENT_NAME = "recipient_name";

  private String             recipientName;
  private MediaSendViewModel viewModel;
  private Controller         controller;
  private GridLayoutManager  layoutManager;

  MediaPickerFolderAdapter adapter;


  public static @NonNull MediaPickerFolderFragment newInstance(@NonNull Recipient recipient) {
    Bundle args = new Bundle();
    args.putString(KEY_RECIPIENT_NAME, RecipientNamesKt.displayName(recipient));

    MediaPickerFolderFragment fragment = new MediaPickerFolderFragment();
    fragment.setArguments(args);

    return fragment;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    recipientName = getArguments().getString(KEY_RECIPIENT_NAME);
    viewModel = new ViewModelProvider(requireActivity()).get(MediaSendViewModel.class);
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);

    if (!(getActivity() instanceof Controller)) {
      throw new IllegalStateException("Parent activity must implement controller class.");
    }

    controller = (Controller) getActivity();
  }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.mediapicker_folder_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    ViewUtilitiesKt.applySafeInsetsPaddings(view);

    RecyclerView             list    = view.findViewById(R.id.mediapicker_folder_list);
    adapter = new MediaPickerFolderAdapter(Glide.with(this), this);

    layoutManager = new GridLayoutManager(requireContext(), 2);
    onScreenWidthChanged(getScreenWidth());

    list.setLayoutManager(layoutManager);
    list.setAdapter(adapter);

    viewModel.getFolders(requireContext()).observe(getViewLifecycleOwner(), adapter::setFolders);

    initToolbar(view.findViewById(R.id.mediapicker_toolbar));
  }
  
  @Override
  public void onResume() {
    super.onResume();

    viewModel.onFolderPickerStarted();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    onScreenWidthChanged(getScreenWidth());
  }

  private void initToolbar(Toolbar toolbar) {
    ((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);
    ActionBar actionBar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (actionBar == null) {
      Log.w("MediaPickerFolderFragment", "ActionBar is null in initToolbar - cannot continue.");
    } else {
      CharSequence txt = Phrase.from(requireContext(), R.string.attachmentsSendTo).put(NAME_KEY, recipientName).format();
      actionBar.setTitle(txt);
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setHomeButtonEnabled(true);
      toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
    }

    if(!AttachmentManager.hasFullAccess(requireActivity())){
      MenuHost menuHost = (MenuHost) requireActivity();
      menuHost.addMenuProvider(new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
          inflater.inflate(R.menu.menu_media_add, menu);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
          if (item.getItemId() == R.id.mediapicker_menu_add) {
            AttachmentManager.managePhotoAccess(requireActivity(), () -> {
              if (!isAdded()) return;
              viewModel.getFolders(requireContext())
                      .observe(getViewLifecycleOwner(), adapter::setFolders);
            });
            return true;
          }
          return false;
        }
      }, getViewLifecycleOwner(), Lifecycle.State.STARTED);
    }
  }

  private void onScreenWidthChanged(int newWidth) {
    if (layoutManager != null) {
      layoutManager.setSpanCount(newWidth / getResources().getDimensionPixelSize(R.dimen.media_picker_folder_width));
    }
  }

  private int getScreenWidth() {
    Point size = new Point();
    requireActivity().getWindowManager().getDefaultDisplay().getSize(size);
    return size.x;
  }

  @Override
  public void onFolderClicked(@NonNull MediaFolder folder) {
    controller.onFolderSelected(folder);
  }

  public interface Controller {
    void onFolderSelected(@NonNull MediaFolder folder);
  }
}
