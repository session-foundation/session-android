package org.thoughtcrime.securesms.util

import coil3.decode.DecodeUtils
import coil3.gif.isGif
import coil3.gif.isWebP
import okio.BufferedSource
import okio.ByteString

object ImageUtils {
    private val JPEG_MAGICS = listOf(
        ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
        ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()),
        ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xEE.toByte()),
        ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()),
    )

    private val PNG_MAGIC = ByteString.of(
        0x89.toByte(),
        0x50.toByte(),
        0x4E.toByte(),
        0x47.toByte(),
        0x0D.toByte(),
        0x0A.toByte(),
        0x1A.toByte(),
        0x0A.toByte()
    )


    /**
     * Returns true if the provided buffer contains a JPEG image. The buffer is not consumed.
     */
    fun isJpeg(buffer: BufferedSource): Boolean {
        return JPEG_MAGICS.any { buffer.rangeEquals(0, it) }
    }

    /**
     * Returns true if the provided buffer contains a PNG image. The buffer is not consumed.
     */
    fun isPng(buffer: BufferedSource): Boolean {
        return buffer.rangeEquals(0, PNG_MAGIC)
    }

    /**
     * Returns true if the provided buffer contains a GIF image. The buffer is not consumed.
     */
    fun isGif(buffer: BufferedSource): Boolean = DecodeUtils.isGif(buffer)

    /**
     * Returns true if the provided buffer contains a WebP image. The buffer is not consumed.
     */
    fun isWebP(buffer: BufferedSource): Boolean = DecodeUtils.isWebP(buffer)
}