package org.thoughtcrime.securesms.util

import android.content.Context
import android.net.Uri
import coil3.decode.DecodeUtils
import coil3.gif.isAnimatedWebP
import network.loki.messenger.libsession_util.image.GifUtils
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream

/**
 * A class offering helper methods relating to animated images
 */
object AnimatedImageUtils {
    fun isAnimated(context: Context, uri: Uri): Boolean {
        return context.contentResolver.openInputStream(uri)?.source()?.buffer()?.use {
            isAnimatedGif(it) || isAnimatedWebP(it)
        } == true
    }

    fun isAnimated(rawImageData: ByteArray): Boolean {
        return ByteArrayInputStream(rawImageData).source().buffer().use {
            isAnimatedGif(it) || isAnimatedWebP(it)
        }
    }

    /**
     * Returns true if the provided buffer contains an animated WebP image. The buffer is not consumed.
     */
    fun isAnimatedWebP(buffer: BufferedSource): Boolean = DecodeUtils.isAnimatedWebP(buffer)

    /**
     * Returns true if the provided buffer contains an animated GIF image. The buffer is not consumed.
     */
    fun isAnimatedGif(buffer: BufferedSource): Boolean {
        return buffer.peek().inputStream().use(GifUtils::isAnimatedGif)
    }
}