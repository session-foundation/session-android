package org.thoughtcrime.securesms.util

import okio.BufferedSource

object ImageUtils {
    // A RIFF image container type where we need to do further checking for WEBP
    private const val RIFF_TYPE = "riff"

    // A sorted list of (MIME type, magic bytes) tuples for image types we want to recognize.
    // Sorted by length of magic bytes (shortest first) to optimize matching.
    private val IMAGE_MAGICS = sequenceOf(
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()),
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xEE.toByte()),
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()),
        "image/png" to byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()),
        "image/gif" to byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte()),
        RIFF_TYPE to byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()),
    ).sortedBy { it.second.size }.toList()


    fun getImageMimeType(buffer: BufferedSource): String? {
        val peeked = buffer.peek()

        // Read enough bytes to match the longest magic number.
        var readBytes = peeked.readByteArray(IMAGE_MAGICS.last().second.size.toLong())

        val matched = IMAGE_MAGICS.firstOrNull { readBytes.startsWith(it.second) }?.first ?: return null

        if (matched == RIFF_TYPE) {
            if (readBytes.size < 12) {
                // WebP's header is "RIFF + [u32 fileSize] + WEBP" so it's 12 bytes long.
                // Read enough bytes for RIFF + WEBP check
                readBytes += peeked.readByteArray(12L - readBytes.size.toLong())
            }

            // Further check for WEBP inside RIFF
            val webpHeader = byteArrayOf(0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte()) // "WEBP"
            if (readBytes.sliceArray(8 until 12).contentEquals(webpHeader)) {
                return "image/webp"
            }

            return null
        }

        return matched
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}