package org.thoughtcrime.securesms.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.thoughtcrime.securesms.ui.components.OffscreenAvatarRenderer
import org.thoughtcrime.securesms.util.AvatarUIData
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AvatarBitmapCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val offscreenAvatarRenderer: Provider<OffscreenAvatarRenderer>,
) {
    private val cache = object : LruCache<AvatarUIData, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: AvatarUIData, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun get(avatarUIData: AvatarUIData): Bitmap {
        cache[avatarUIData]?.let { return it }

        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        offscreenAvatarRenderer.get().render(bitmap, avatarUIData)
        cache.put(avatarUIData, bitmap)
        return bitmap
    }
}
