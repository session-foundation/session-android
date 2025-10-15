package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.size.Precision
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.experimental.and
import kotlin.math.roundToInt

typealias DigestResult = ByteArray

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

    suspend fun process(
        mimeType: String,
        data: () -> InputStream,
        maxImageResolution: IntSize?,
        compressImage: Boolean,
    ): ProcessResult? {
        when {
            mimeType.startsWith("image/gif", ignoreCase = true) -> {
                if (maxImageResolution == null) {
                    Log.d(TAG, "Skipping processing of GIF with no size constraints")
                    return null
                }

                return processGif(data(), maxImageResolution)
            }

            mimeType.startsWith("image/jpeg", ignoreCase = true) ||
                    mimeType.startsWith("image/jpg", ignoreCase = true) -> {
                val (data, imageSize) = processStaticImage(
                    mimeType = mimeType,
                    data = data(),
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

            mimeType.startsWith("image/png", ignoreCase = true) -> {
                val (data, imageSize) = processStaticImage(
                    mimeType = mimeType,
                    data = data(),
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

            mimeType.startsWith("image/webp", ignoreCase = true) -> {
                // For webp, we'll have to find out if it's animated upfront to avoid
                // extra costly decoding.
                val (isAnimated, updatedInputStream) = isWebPAnimation(data())
                if (isAnimated) {
                    return processAnimatedWebP(updatedInputStream, maxImageResolution)
                }

                val (data, imageSize) = processStaticImage(
                    mimeType = mimeType,
                    data = updatedInputStream,
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
        maxImageResolution: IntSize?,
    ): ProcessResult {
        TODO("Not yet implemented")
    }

    private suspend fun loadImageFromCoil(
        data: InputStream,
        mimeType: String,
        maxImageResolution: IntSize?,
    ): ImageResult {
        val builder = ImageRequest.Builder(context)
            .allowHardware(false)
            .allowRgb565(true)
            .allowConversionToBitmap(true)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .fetcherFactory(InputStreamFetcherFactory(data, mimeType))

        if (maxImageResolution != null) {
            builder.size(maxImageResolution.width, maxImageResolution.height)
                .precision(Precision.INEXACT)
        }

        return imageLoader.get().execute(builder.data(data).build())
    }

    private suspend fun processStaticImage(
        mimeType: String,
        data: InputStream,
        maxImageResolution: IntSize?,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Pair<ByteArray, IntSize> {
        val result = loadImageFromCoil(data, mimeType, maxImageResolution)
        val bitmap = checkNotNull(result.image as? BitmapImage) {
            "Expected a BitmapImage but got ${result.image?.javaClass}"
        }.bitmap

        return ByteArrayOutputStream().also { out ->
            bitmap.compress(format, quality, out)
        }.toByteArray() to IntSize(bitmap.width, bitmap.height)
    }

    private fun processGif(
        data: InputStream,
        maxImageResolution: IntSize
    ): ProcessResult? {
        val movie = data.use(Movie::decodeStream)

        // If the GIF is already within the size limits, no need to process it.
        if (movie.width() <= maxImageResolution.width &&
            movie.height() <= maxImageResolution.height) {
            return null
        }

        // Work out the scale to fit within the max dimensions.
        val scale = minOf(
            maxImageResolution.width.toDouble() / movie.width().toDouble(),
            maxImageResolution.height.toDouble() / movie.height().toDouble()
        )

        val totalDuration = movie.duration()
        val targetWidth = (movie.width() * scale).roundToInt()
        val targetHeight = (movie.height() * scale).roundToInt()
        val frameBitmap = createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val deadline = SystemClock.elapsedRealtime() + 10_000 // 10 seconds
        val frameIntervalMills = 1000 / 20L // Target 20 FPS
        val frameBytes = IntArray(targetWidth * targetHeight)
        val imageOptions = ImageOptions()
        val frameCanvas = Canvas(frameBitmap)
        frameCanvas.scale(scale.toFloat(), scale.toFloat())

        val compressed = ByteArrayOutputStream().use { outputStream ->
            val gifEncoder = GifEncoder(outputStream, targetWidth, targetHeight, 1)

            var time = 0
            while (time < totalDuration) {
                movie.setTime(time)
                frameBitmap.eraseColor(0) // Clear to get ready for next frame
                movie.draw(frameCanvas, 0f, 0f, null)


                frameBitmap.getPixels(frameBytes, 0, targetWidth, 0, 0, targetWidth, targetHeight)

                imageOptions.setDelay(if (time == 0) 0L else frameIntervalMills, TimeUnit.MILLISECONDS)
                gifEncoder.addImage(frameBytes, targetWidth, imageOptions)

                time += frameIntervalMills.toInt()

                if (SystemClock.elapsedRealtime() > deadline) {
                    Log.w(TAG, "Given up downsizing GIF as it took too long")
                    frameBitmap.recycle()
                    return null
                }
            }

            gifEncoder.finishEncoding()
            outputStream.toByteArray()
        }

        frameBitmap.recycle()

        return ProcessResult(
            data = compressed,
            mimeType = "image/gif",
            imageSize = IntSize(targetWidth, targetHeight)
        )
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

    private class InputStreamFetcherFactory(
        private val stream: InputStream,
        private val mimeType: String
    ) : Fetcher.Factory<InputStream> {
        override fun create(
            data: InputStream,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return object : Fetcher {
                override suspend fun fetch(): FetchResult? {
                    return SourceFetchResult(
                        source = ImageSource(source = stream.source().buffer(), FileSystem.SYSTEM),
                        mimeType = mimeType,
                        dataSource = DataSource.MEMORY
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "AttachmentProcessor"
    }
}