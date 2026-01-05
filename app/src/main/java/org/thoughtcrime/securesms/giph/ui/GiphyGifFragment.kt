package org.thoughtcrime.securesms.giph.ui;


import android.os.Bundle;
import androidx.loader.content.Loader;

import org.thoughtcrime.securesms.giph.model.GiphyImage;
import org.thoughtcrime.securesms.giph.net.GiphyGifLoader;

import java.util.List;

@Suppress("UNCHECKED_CAST")
class GiphyGifFragment : GiphyFragment() {
    override fun onCreateLoader(
        id: Int,
        args: Bundle?
    ): Loader<List<GiphyImage>> {
        return GiphyGifLoader(requireActivity(), searchString)
                as Loader<List<GiphyImage>>
    }
}