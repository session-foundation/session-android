package org.thoughtcrime.securesms.mediasend;

import static org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY;

import androidx.appcompat.app.ActionBar;
import androidx.lifecycle.ViewModelProvider;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.bumptech.glide.Glide;
import com.squareup.phrase.Phrase;

import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.guava.Optional;
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

  public static @NonNull MediaPickerFolderFragment newInstance(@NonNull Recipient recipient) {
    String name = Optional.fromNullable(recipient.getName())
                          .or(Optional.fromNullable(recipient.getProfileName()))
                          .or(recipient.getName());

    Bundle args = new Bundle();
    args.putString(KEY_RECIPIENT_NAME, name);

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
    MediaPickerFolderAdapter adapter = new MediaPickerFolderAdapter(Glide.with(this), this);

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
