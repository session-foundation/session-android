package org.thoughtcrime.securesms.util

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build

/**
 * A class offering helper methods relating to animated images
 */
object AnimatedImageUtils {
    fun isAnimated(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)
        return when (mime) {
            "image/gif"  -> isAnimatedImage(context, uri) // not all gifs are animated
            "image/webp" -> isAnimatedImage(context, uri) // not all WebPs are animated
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
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(32)
            if (input.read(header) != header.size) return false

            // Bytes 12-15 contain “VP8X”
            val isVp8x = header.sliceArray(12..15).contentEquals("VP8X".toByteArray())

            if (isVp8x) {
                /* 21st byte (index 20) holds the feature flags; bit #1 = animation */
                val animationFlagSet = header[21].toInt() and 0x02 != 0
                if (animationFlagSet) return true            // animated!
            }

            // Fallback scan for literal “ANIM” in header area
            return header.asList().windowed(4).any { it.toByteArray().contentEquals("ANIM".toByteArray()) }
        }
        return false
    }
}