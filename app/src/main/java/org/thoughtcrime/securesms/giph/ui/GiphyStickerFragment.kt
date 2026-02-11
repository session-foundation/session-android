package org.thoughtcrime.securesms.giph.ui;


import android.os.Bundle;
import androidx.loader.content.Loader;

import org.thoughtcrime.securesms.giph.model.GiphyImage;
import org.thoughtcrime.securesms.giph.net.GiphyStickerLoader;

import java.util.List;

@Suppress("UNCHECKED_CAST")
class GiphyStickerFragment : GiphyFragment() {
    override fun onCreateLoader(
        id: Int,
        args: Bundle?
    ): Loader<List<GiphyImage>> {
        return GiphyStickerLoader(requireActivity(), searchString)
                as Loader<List<GiphyImage>>
    }
}