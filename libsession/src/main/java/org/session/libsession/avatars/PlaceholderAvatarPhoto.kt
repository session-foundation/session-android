package org.session.libsession.avatars

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.bumptech.glide.load.Key
import java.security.MessageDigest

class PlaceholderAvatarPhoto(
    val hashString: String,
    val displayName: String,
    val bitmap: BitmapDrawable
    ): Key {
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(hashString.encodeToByteArray())
        messageDigest.update(displayName.encodeToByteArray())
    }
}