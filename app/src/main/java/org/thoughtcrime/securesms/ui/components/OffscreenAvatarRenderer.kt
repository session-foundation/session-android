package org.thoughtcrime.securesms.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.R
import org.session.libsession.utilities.recipients.RemoteFile
import org.thoughtcrime.securesms.ui.theme.classicDark3
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement
import org.thoughtcrime.securesms.util.avatarOptions
import javax.inject.Inject

/**
 * Renders [AvatarUIData] to a [Bitmap] without using Compose, mirroring the layout and
 * appearance of the [Avatar] composable.
 */
class OffscreenAvatarRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) {
    /**
     * Render the avatar described by [data] into a new [Bitmap] of [sizePx] × [sizePx] pixels.
     */
    suspend fun render(bitmap: Bitmap, data: AvatarUIData) {
        val sizePx = minOf(bitmap.width, bitmap.height)
        val canvas = Canvas(bitmap)

        when {
            data.elements.isEmpty() -> { /* nothing */ }

            data.elements.size == 1 -> {
                drawElement(canvas, data.elements.first(), 0f, 0f, sizePx.toFloat())
            }

            else -> {
                // Two elements at 78% size, first top-start, second bottom-end
                val elemSize = sizePx * 0.78f
                drawElement(canvas, data.elements[0], 0f, 0f, elemSize)
                val offset = sizePx - elemSize
                drawElement(canvas, data.elements[1], offset, offset, elemSize)
            }
        }
    }

    private suspend fun drawElement(
        canvas: Canvas,
        element: AvatarUIElement,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val cx = left + size / 2f
        val cy = top + size / 2f
        val radius = size / 2f

        // Try to load custom image first
        when (val content = element.content) {
            is AvatarUIElement.RemoteFileContent -> {
                val image = loadRemoteFile(content.remoteFile, size.toInt())
                if (image != null) {
                    drawImageInCircle(canvas, image, left, top, size)
                    return
                }
                // Fall through to fallback
            }

            is AvatarUIElement.BitmapContent -> {
                drawCircleBitmap(canvas, content.bitmap, left, top, size)
                return
            }

            null -> { /* use fallback */ }
        }

        drawFallback(canvas, element.fallback, left, top, size, cx, cy, radius)
    }

    private fun drawFallback(
        canvas: Canvas,
        fallback: AvatarUIElement.Fallback,
        left: Float,
        top: Float,
        size: Float,
        cx: Float,
        cy: Float,
        radius: Float,
    ) {
        @ColorInt val bgColor = fallback.color?.toArgb() ?: CLASSIC_DARK_3

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        val padding = size * 0.2f
        val innerRect = RectF(left + padding, top + padding, left + size - padding, top + size - padding)

        when {
            // 1. Icon resource
            fallback.icon != null -> {
                drawDrawableInRect(canvas, fallback.icon, innerRect)
            }

            // 2. Name/initials text
            !fallback.name.isNullOrEmpty() -> {
                drawCenteredText(canvas, fallback.name, left, top, size, padding)
            }

            // 3. Default unknown user icon
            else -> {
                drawDrawableInRect(canvas, R.drawable.ic_user_filled_custom, innerRect)
            }
        }
    }

    /**
     * Draw a Coil [coil3.Image] cropped to a circle, using [coil3.Image.draw] for rendering.
     */
    private fun drawImageInCircle(canvas: Canvas, image: coil3.Image, left: Float, top: Float, size: Float) {
        val circlePath = Path().apply {
            addCircle(left + size / 2f, top + size / 2f, size / 2f, Path.Direction.CW)
        }

        canvas.withClip(circlePath) {
            withSave {
                // Center-crop: scale so the shorter dimension fills the target, then center.
                val scale = maxOf(size / image.width, size / image.height)
                val scaledW = image.width * scale
                val scaledH = image.height * scale
                val dx = left + (size - scaledW) / 2f
                val dy = top + (size - scaledH) / 2f

                translate(dx, dy)
                scale(scale, scale)
                image.draw(this)
            }
        }
    }

    /**
     * Draw a bitmap cropped to a circle, matching Compose's ContentScale.Crop + CircleShape clip.
     */
    private fun drawCircleBitmap(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, size: Float) {
        val circlePath = Path().apply {
            addCircle(left + size / 2f, top + size / 2f, size / 2f, Path.Direction.CW)
        }

        canvas.withClip(circlePath) {
            // Center-crop: scale so the shorter dimension fills the target, then center.
            val scale = maxOf(size / bitmap.width, size / bitmap.height)
            val scaledW = bitmap.width * scale
            val scaledH = bitmap.height * scale
            val dx = left + (size - scaledW) / 2f
            val dy = top + (size - scaledH) / 2f

            val dst = RectF(dx, dy, dx + scaledW, dy + scaledH)
            drawBitmap(bitmap, null, dst, BITMAP_PAINT)
        }
    }

    private fun drawDrawableInRect(canvas: Canvas, drawableRes: Int, rect: RectF) {
        val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return
        canvas.withSave {
            drawable.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            drawable.setTint(android.graphics.Color.WHITE)
            drawable.draw(this)
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        size: Float,
        padding: Float,
    ) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            // Start at 50% of size and shrink if needed to fit within the padded area
            textSize = size * 0.5f
        }

        val maxWidth = size - padding * 2

        // Shrink text size until it fits (matching Compose's auto-size behaviour)
        while (textPaint.measureText(text) > maxWidth && textPaint.textSize > size * 0.1f) {
            textPaint.textSize -= 1f
        }

        val x = left + size / 2f
        val y = top + size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
    }

    private suspend fun loadRemoteFile(remoteFile: RemoteFile, sizePx: Int): coil3.Image? {
        val request = ImageRequest.Builder(context)
            .data(remoteFile)
            .allowHardware(false)
            .allowRgb565(true)
            .avatarOptions(sizePx, freezeFrame = true)
            .build()

        return (imageLoader.execute(request) as? SuccessResult)?.image
    }

    private companion object {
        // classicDark3 = Color(0xff414141) → ARGB int
        @ColorInt
        val CLASSIC_DARK_3: Int = classicDark3.toArgb()

        val BITMAP_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}
