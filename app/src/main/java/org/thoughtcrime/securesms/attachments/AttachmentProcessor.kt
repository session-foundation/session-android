package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Movie
import android.text.format.Formatter
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.DecodeUtils
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.isAnimatedWebP
import coil3.request.CachePolicy
import coil3.request.ImageRequest.Builder
import coil3.request.Options
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.size.Precision
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.libsession_util.encrypt.Attachments
import network.loki.messenger.libsession_util.image.GifUtils
import network.loki.messenger.libsession_util.image.WebPUtils
import okio.BufferedSource
import okio.FileSystem
import org.session.libsession.utilities.Util
import org.session.libsignal.streams.AttachmentCipherInputStream
import org.session.libsignal.streams.AttachmentCipherOutputStream
import org.session.libsignal.streams.PaddingInputStream
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.ImageUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.roundToInt

typealias DigestResult = ByteArray

/**
 * A central class to handle attachment resizing/exif stripping/compression/encryption, etc.
 */
@Singleton
class AttachmentProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageLoader: Provider<ImageLoader>,
) {
    class ProcessResult(
        val data: ByteArray,
        val mimeType: String,
        val imageSize: IntSize
    )

    /**
     * Process a file based on its mime type and the given constraints.
     *
     * @param data The data to process. This method will **NOT** close the source for you. If
     * this function returns null, the source will remain unconsumed.
     *
     * @return null if nothing was done, or a ProcessResult if processing was performed.
     */
    suspend fun process(
        data: BufferedSource,
        maxImageResolution: IntSize?,
        compressImage: Boolean,
    ): ProcessResult? {
        val mimeType = ImageUtils.getImageMimeType(data) ?: run {
            Log.d(TAG, "No image detected based on magic bytes, skipping processing")
            return null
        }

        Log.d(TAG, "Processing $mimeType with max resolution $maxImageResolution")

        when {
            mimeType.startsWith("image/gif") -> {
                if (maxImageResolution == null) {
                    Log.d(TAG, "Skipping processing of GIF with no size constraints")
                    return null
                }

                return processGif(data, maxImageResolution)
            }

            mimeType.startsWith("image/jpeg") -> {
                val (data, imageSize) = processStaticImage(
                    mimeType = mimeType,
                    data = data,
                    maxImageResolution = maxImageResolution,
                    format = Bitmap.CompressFormat.JPEG,
                    quality = if (compressImage) 75 else 95,
                )
                return ProcessResult(
                    data = data,
                    imageSize = imageSize,
                    mimeType = mimeType,
                )
            }

            mimeType.startsWith("image/png") -> {
                val (data, imageSize) = processStaticImage(
                    mimeType = mimeType,
                    data = data,
                    maxImageResolution = maxImageResolution,
                    format = if (android.os.Build.VERSION.SDK_INT >= 30)
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    else
                        Bitmap.CompressFormat.WEBP,
                    quality = if (compressImage) 75 else 95,
                )

                return ProcessResult(
                    data = data,
                    imageSize = imageSize,
                    mimeType = "image/webp",
                )
            }

            mimeType.startsWith("image/webp") -> {
                if (DecodeUtils.isAnimatedWebP(data)) {
                    if (maxImageResolution == null) {
                        Log.d(TAG, "Skipping processing of animated WebP with no size constraints")
                        return null
                    }

                    return processAnimatedWebP(data = data.readByteArray(), maxImageResolution)
                }

                val (data, imageSize) = processStaticImage(
                    mimeType = mimeType,
                    data = data,
                    maxImageResolution = maxImageResolution,
                    format = Bitmap.CompressFormat.WEBP,
                    quality = if (compressImage) 75 else 95,
                )

                return ProcessResult(
                    data = data,
                    imageSize = imageSize,
                    mimeType = mimeType,
                )
            }

            else -> return null // No processing needed
        }
    }


    class EncryptResult(
        val ciphertext: ByteArray,
        val key: ByteArray,
    )

    /**
     * Encrypt the given data using deterministic encryption from libsession.
     */
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

    /**
     * Encrypt the given data using the legacy attachment encryption method, returning a digest
     * for the encrypted data too.
     */
    fun encryptAttachmentLegacy(plaintext: ByteArray): Pair<EncryptResult, DigestResult> {
        val key = Util.getSecretBytes(64)
        var remainingPaddingSize =
            (PaddingInputStream.getPaddedSize(plaintext.size.toLong()) - plaintext.size.toLong()).toInt()
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

    fun decryptAttachmentLegacy(
        ciphertext: ByteArraySlice,
        key: ByteArray,
        digest: ByteArray?
    ): ByteArraySlice {
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

    private fun scaleToFit(original: IntSize, max: IntSize): Pair<IntSize, Float> {
        val scale = minOf(
            max.width.toDouble() / original.width.toDouble(),
            max.height.toDouble() / original.height.toDouble()
        )

        return IntSize(
            width = (original.width * scale).roundToInt(),
            height = (original.height * scale).roundToInt()
        ) to scale.toFloat()
    }

    private fun processAnimatedWebP(
        data: ByteArray,
        maxImageResolution: IntSize?,
    ): ProcessResult? {
        val origSize = BitmapUtil.getDimensions(ByteArrayInputStream(data))
            .let { pair -> IntSize(pair.first, pair.second) }

        val targetSize: IntSize

        if (maxImageResolution == null || (
                    origSize.width <= maxImageResolution.width &&
                            origSize.height <= maxImageResolution.height)
        ) {
            // No resizing needed
            targetSize = origSize
        } else {
            targetSize = scaleToFit(
                original = origSize,
                max = maxImageResolution
            ).first
        }

        val start = System.currentTimeMillis()

        val reencoded = WebPUtils.reencodeWebPAnimation(
            input = data,
            targetWidth = targetSize.width,
            targetHeight = targetSize.height,
            timeoutMills = 10_000L,
        )

        Log.d(
            TAG,
            "Re-encoded animated WebP from ${origSize.width}x${origSize.height} to ${targetSize.width}x${targetSize.height} " +
                    "in ${System.currentTimeMillis() - start}ms, original size=${
                        Formatter.formatFileSize(
                            context,
                            data.size.toLong()
                        )
                    }, " +
                    "new size=${Formatter.formatFileSize(context, reencoded.size.toLong())}"
        )

        return ProcessResult(
            data = reencoded,
            mimeType = "image/webp",
            imageSize = targetSize
        )
    }

    private suspend fun processStaticImage(
        mimeType: String,
        data: Any,
        maxImageResolution: IntSize?,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Pair<ByteArray, IntSize> {
        val builder = Builder(context)
            .allowHardware(false)
            .allowRgb565(true)
            .allowConversionToBitmap(true)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .fetcherFactory(BufferedSourceFetcherFactory(mimeType))

        if (maxImageResolution != null) {
            builder.size(maxImageResolution.width, maxImageResolution.height)
                .precision(Precision.INEXACT)
        }

        val result = imageLoader.get().execute(builder.data(data).build())
        val bitmap = checkNotNull(result.image as? BitmapImage) {
            "Expected a BitmapImage but got ${result.image?.javaClass}"
        }.bitmap

        return ByteArrayOutputStream().also { out ->
            bitmap.compress(format, quality, out)
        }.toByteArray() to IntSize(bitmap.width, bitmap.height)
    }

    @Suppress("DEPRECATION")
    private fun processGif(
        data: ByteArray,
        maxImageResolution: IntSize?
    ): ProcessResult? {
        val origSize = BitmapUtil.getDimensions(ByteArrayInputStream(data))
            .let { pair -> IntSize(pair.first, pair.second) }

        val targetSize: IntSize

        if (maxImageResolution == null || (
                    origSize.width <= maxImageResolution.width &&
                            origSize.height <= maxImageResolution.height)
        ) {
            // No resizing needed
            targetSize = origSize
        } else {
            targetSize = scaleToFit(
                original = origSize,
                max = maxImageResolution
            ).first
        }

        val start = System.currentTimeMillis()

        val reencoded = GifUtils.reencodeGif(
            input = data,
            targetWidth = targetSize.width,
            targetHeight = targetSize.height,
            timeoutMills = 10_000L,
        )

        Log.d(
            TAG,
            "Re-encoded animated gif from ${origSize.width}x${origSize.height} to ${targetSize.width}x${targetSize.height} " +
                    "in ${System.currentTimeMillis() - start}ms, original size=${
                        Formatter.formatFileSize(
                            context,
                            data.size.toLong()
                        )
                    }, " +
                    "new size=${Formatter.formatFileSize(context, reencoded.size.toLong())}"
        )

        return ProcessResult(
            data = reencoded,
            mimeType = "image/gif",
            imageSize = targetSize
        )
    }

    /**
     * A locally scoped fetcher to allow us to use Coil's for decoding an inputStream.
     */
    private class BufferedSourceFetcherFactory(
        private val mimeType: String
    ) : Fetcher.Factory<BufferedSource> {
        override fun create(
            data: BufferedSource,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return object : Fetcher {
                override suspend fun fetch(): FetchResult? {
                    return SourceFetchResult(
                        source = ImageSource(data, FileSystem.SYSTEM),
                        mimeType = mimeType,
                        dataSource = DataSource.MEMORY
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "AttachmentProcessor"

        val MAX_AVATAR_SIZE_PX = IntSize(600, 600)
    }
}