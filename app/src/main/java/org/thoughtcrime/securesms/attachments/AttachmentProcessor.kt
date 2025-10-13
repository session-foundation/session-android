package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.size.Precision
import coil3.size.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.libsession_util.encrypt.Attachments
import okio.FileSystem
import okio.buffer
import okio.source
import org.session.libsession.utilities.Util
import org.session.libsignal.streams.AttachmentCipherInputStream
import org.session.libsignal.streams.AttachmentCipherOutputStream
import org.session.libsignal.streams.PaddingInputStream
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.experimental.and

typealias DigestResult = ByteArray

@Singleton
class AttachmentProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageLoader: Provider<ImageLoader>,
) {
    class ProcessResult(
        val data: ByteArray,
        val mimeType: String,
    )

    suspend fun process(
        mimeType: String,
        data: InputStream,
        maxImageResolution: Size?,
        compressImage: Boolean,
    ): ProcessResult {
        when {
            mimeType.startsWith("image/gif", ignoreCase = true) -> {
                if (maxImageResolution == null) {
                    Log.d(TAG, "Skipping processing of GIF with no size constraints")
                    return ProcessResult(
                        data = data.readBytes(),
                        mimeType = mimeType,
                    )
                }

                return processGif(data, maxImageResolution)
            }

            mimeType.startsWith("image/jpeg", ignoreCase = true) ||
                    mimeType.startsWith("image/jpg", ignoreCase = true) -> {
                return ProcessResult(
                    data = processStaticImage(
                        mimeType = mimeType,
                        data = data,
                        maxImageResolution = maxImageResolution,
                        format = Bitmap.CompressFormat.JPEG,
                        quality = if (compressImage) 75 else 100,
                    ),
                    mimeType = mimeType,
                )
            }

            mimeType.startsWith("image/png", ignoreCase = true) -> {
                return ProcessResult(
                    data = processStaticImage(
                        mimeType = mimeType,
                        data = data,
                        maxImageResolution = maxImageResolution,
                        format = if (android.os.Build.VERSION.SDK_INT >= 30)
                            Bitmap.CompressFormat.WEBP_LOSSLESS
                        else
                            Bitmap.CompressFormat.WEBP,
                        quality = if (compressImage) 75 else 100,
                    ),
                    mimeType = "image/webp",
                )
            }

            mimeType.startsWith("image/webp", ignoreCase = true) -> {
                // For webp, we'll have to find out if it's animated upfront to avoid
                // extra costly decoding.
                val (isAnimated, updatedInputStream) = isWebPAnimation(data)
                if (isAnimated) {
                    return processAnimatedWebP(updatedInputStream, maxImageResolution)
                }

                return ProcessResult(
                    data = processStaticImage(
                        mimeType = mimeType,
                        data = updatedInputStream,
                        maxImageResolution = maxImageResolution,
                        format = Bitmap.CompressFormat.WEBP,
                        quality = if (compressImage) 75 else 100,
                    ),
                    mimeType = mimeType,
                )
            }

            else -> {
                return ProcessResult(
                    data = data.readBytes(),
                    mimeType = mimeType,
                )
            }
        }
    }


    class EncryptResult(
        val ciphertext: ByteArray,
        val key: ByteArray,
    )

    fun encryptDeterministically(plaintext: ByteArray, domain: Attachments.Domain): EncryptResult {
        val cipherOut = ByteArray(Attachments.encryptedSize(plaintext.size.toLong()).toInt())
        val key = Attachments.encryptBytes(
            seed = Util.getSecretBytes(32),
            plaintextIn = plaintext,
            cipherOut = cipherOut,
            domain = domain,
        )

        return EncryptResult(
            ciphertext = cipherOut,
            key = key,
        )
    }

    fun encrypt(plaintext: ByteArray): Pair<EncryptResult, DigestResult> {
        val key = Util.getSecretBytes(64)
        var remainingPaddingSize = (PaddingInputStream.getPaddedSize(plaintext.size.toLong()) - plaintext.size.toLong()).toInt()
        val paddingBuffer = ByteArray(remainingPaddingSize.coerceAtMost(512))
        val digest: ByteArray

        val cipherText = ByteArrayOutputStream().also { outputStream ->
            AttachmentCipherOutputStream(key, outputStream).use { os ->
                os.write(plaintext)

                while (remainingPaddingSize > 0) {
                    val toWrite = remainingPaddingSize.coerceAtMost(paddingBuffer.size)
                    os.write(paddingBuffer, 0, toWrite)
                    remainingPaddingSize -= toWrite
                }

                os.flush()

                digest = os.transmittedDigest
            }
        }.toByteArray()

        return EncryptResult(
            ciphertext = cipherText,
            key = key,
        ) to digest
    }

    fun decryptDeterministically(ciphertext: ByteArraySlice, key: ByteArray): ByteArraySlice {
        val plaintextOut = ByteArray(
            requireNotNull(Attachments.decryptedMaxSizeOrNull(ciphertext.len.toLong())) {
                "Ciphertext size ${ciphertext.len} is too small to be valid"
            }.toInt()
        )

        val plaintextSize = Attachments.decryptBytes(
            key = key,
            cipherIn = ciphertext.data,
            cipherInOffset = ciphertext.offset,
            cipherInLen = ciphertext.len,
            plainOut = plaintextOut,
            plainOutOffset = 0,
            plainOutLen = plaintextOut.size,
        ).toInt()

        return plaintextOut.view(0 until plaintextSize)
    }

    fun decrypt(ciphertext: ByteArraySlice, key: ByteArray, digest: ByteArray?): ByteArraySlice {
        return AttachmentCipherInputStream.createForAttachment(ciphertext, key, digest)
            .use { it.readBytes().view() }
    }

    fun digest(data: ByteArray): ByteArray {
        try {
            return MessageDigest.getInstance("SHA256").digest(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute SHA256 digest", e)
            return byteArrayOf()
        }
    }


    private fun processAnimatedWebP(
        updatedInputStream: InputStream,
        maxImageResolution: Size?
    ): ProcessResult {
        TODO("Not yet implemented")
    }

    private suspend fun processStaticImage(
        mimeType: String,
        data: InputStream,
        maxImageResolution: Size?,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray {
        val builder = ImageRequest.Builder(context)
            .allowHardware(false)
            .allowRgb565(true)
            .allowConversionToBitmap(true)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .fetcherFactory<InputStream> { stream, _, _ ->
                object : Fetcher {
                    override suspend fun fetch(): FetchResult? {
                        return SourceFetchResult(
                            source = ImageSource(source = stream.source().buffer(), FileSystem.SYSTEM),
                            mimeType = mimeType,
                            dataSource = DataSource.MEMORY
                        )
                    }
                }
            }

        if (maxImageResolution != null) {
            builder.size(maxImageResolution)
                .precision(Precision.INEXACT)
        }

        val result = imageLoader.get().execute(builder.data(data).build())

        val bitmap = checkNotNull(result.image as? BitmapImage) {
            "Expected a BitmapImage but got ${result.image?.javaClass}"
        }.bitmap

        return ByteArrayOutputStream().also { out ->
            bitmap.compress(format, quality, out)
        }.toByteArray()
    }

    private fun processGif(
        data: InputStream,
        maxImageResolution: Size
    ): ProcessResult {
        TODO("Not yet implemented")
    }

    private fun checkBytes(a: ByteArray, range: IntRange, expect: ByteArray): Boolean {
        for (i in expect.indices) {
            if (a[range.first + i] != expect[i]) {
                return false
            }
        }

        return true
    }

    private fun isWebPAnimation(stream: InputStream): Pair<Boolean, InputStream> {
        val headers = ByteArray(
            4 + 4 + 4 + 4 + 4 + 4 // RIFF + fileSize + WEBP + VP8X + chuck size + flags
        )

        var offset = 0
        while (offset < headers.size) {
            val read = stream.read(headers, offset, headers.size - offset)
            check(read > 0) { "Unexpected EOF while reading from input stream" }
            offset += read
        }

        check(checkBytes(headers, 0 until 4, "RIFF".toByteArray())) {
            "Invalid WebP file, missing RIFF"
        }
        check(checkBytes(headers, 8 until 12, "WEBP".toByteArray())) {
            "Invalid WebP file, missing WEBP"
        }

        val isAnimated = if (checkBytes(headers, 12 until 16, "VP8X".toByteArray())) {
            headers[20] and 0x02 != 0.toByte()
        } else {
            false
        }

        // Once we read something from the input stream, we can't put it back so we'll
        // create a new inputStream that covers both the headers we read and the rest of the stream.
        val updatedInputStream = SequenceInputStream(
            ByteArrayInputStream(headers),
            stream
        )

        return isAnimated to updatedInputStream
    }

    companion object {
        private const val TAG = "AttachmentProcessor"
    }
}