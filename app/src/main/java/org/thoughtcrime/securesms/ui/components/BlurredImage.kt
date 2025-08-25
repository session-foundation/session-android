package org.thoughtcrime.securesms.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * A composable that displays a blurred image, including with legacy compatibility
 * for devices running android < 12
 */
@Composable
fun BlurredImage(
    drawableId: Int,
    blurRadiusDp: Float,
    modifier: Modifier = Modifier,
    alpha: Float = 0.8f
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Use Compose's built-in blur for newer devices.
        Image(
            painter = painterResource(id = drawableId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.blur(blurRadiusDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            alpha = alpha
        )
    } else {
        // Convert and blur vector drawable for legacy devices.
        val bitmap = getBlurredBitmapFromVector(context, drawableId, blurRadiusDp, applyBlur = true)
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier,
                alpha = alpha
            )
        } else {
            // Fallback: show unblurred image.
            Image(
                painter = painterResource(id = drawableId),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier,
                alpha = alpha
            )
        }
    }
}

private fun getBlurredBitmapFromVector(
    context: Context,
    drawableId: Int,
    blurRadius: Float,
    applyBlur: Boolean
): Bitmap? {
    val drawable = AppCompatResources.getDrawable(context, drawableId) ?: return null
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth,
        drawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888
    )
    Canvas(bitmap).apply {
        drawable.setBounds(0, 0, width, height)
        drawable.draw(this)
    }
    return if (applyBlur) {
        blurBitmapLegacy(context, bitmap, blurRadius)
    } else {
        bitmap
    }
}

private fun blurBitmapLegacy(context: Context, bitmap: Bitmap, blurRadius: Float): Bitmap {
    val mutableBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val renderScript = RenderScript.create(context) ?: return mutableBitmap
    try {
        val allocation = Allocation.createFromBitmap(renderScript, mutableBitmap)
        val blurScript = ScriptIntrinsicBlur.create(renderScript, allocation.element)
        blurScript.setRadius(blurRadius)
        blurScript.setInput(allocation)
        blurScript.forEach(allocation)
        allocation.copyTo(mutableBitmap)
    } finally {
        renderScript.destroy()
    }
    return mutableBitmap
}