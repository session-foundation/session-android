package org.thoughtcrime.securesms.util

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import coil3.decode.DecodeUtils
import coil3.gif.isAnimatedWebP
import okio.buffer
import okio.source

/**
 * A class offering helper methods relating to animated images
 */
object AnimatedImageUtils {
    fun isAnimated(context: Context, uri: Uri): Boolean {
        val buffer =
            context.contentResolver.openInputStream(uri)?.source()?.buffer() ?: return false

        val mime = buffer.use(ImageUtils::getImageMimeType)
        return when (mime) {
            "image/gif", "image/webp" -> isAnimatedImage(context, uri) // not all gifs are animated
            else         -> false
        }
    }

    private fun isAnimatedImage(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < 28) return isAnimatedImageLegacy(context, uri)

        var animated = false
        val source = ImageDecoder.createSource(context.contentResolver, uri)

        ImageDecoder.decodeDrawable(source) { _, info, _ ->
            animated = info.isAnimated  // true for GIF & animated WebP
        }

        return animated
    }

    private fun isAnimatedImageLegacy(context: Context, uri: Uri): Boolean {
        context.contentResolver.openInputStream(uri)?.let { input ->
            return DecodeUtils.isAnimatedWebP(input.source().buffer())
        }

        return false
    }
}