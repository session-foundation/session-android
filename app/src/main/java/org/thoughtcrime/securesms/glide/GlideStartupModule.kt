package org.thoughtcrime.securesms.glide

import android.app.Application
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.UnitModelLoader
import com.bumptech.glide.load.resource.bitmap.Downsampler
import com.bumptech.glide.load.resource.bitmap.StreamBitmapDecoder
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.load.resource.gif.StreamGifDecoder
import org.session.libsession.utilities.recipients.RemoteFile
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.giph.model.ChunkedImageUrl
import org.thoughtcrime.securesms.glide.cache.EncryptedBitmapCacheDecoder
import org.thoughtcrime.securesms.glide.cache.EncryptedBitmapResourceEncoder
import org.thoughtcrime.securesms.glide.cache.EncryptedCacheEncoder
import org.thoughtcrime.securesms.glide.cache.EncryptedGifCacheDecoder
import org.thoughtcrime.securesms.glide.cache.EncryptedGifDrawableResourceEncoder
import org.thoughtcrime.securesms.mms.AttachmentStreamUriLoader
import org.thoughtcrime.securesms.mms.AttachmentStreamUriLoader.AttachmentModel
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Provider

class GlideStartupModule @Inject constructor(
    private val context: Application,
    private val remoteFileLoader: Provider<RemoteFileLoader>,
) : OnAppStartupComponent {
    override fun onPostAppStarted() {
        val glide = Glide.get(context)
        val registry = glide.registry

        val secretProvider = AttachmentSecretProvider.getInstance(context)

        registry.prepend(
            File::class.java,
            File::class.java,
            UnitModelLoader.Factory.getInstance()
        )
        registry.prepend(
            InputStream::class.java,
            EncryptedCacheEncoder(secretProvider, glide.arrayPool)
        )
        registry.prepend(
            File::class.java,
            Bitmap::class.java,
            EncryptedBitmapCacheDecoder(
                secretProvider,
                StreamBitmapDecoder(
                    Downsampler(
                        registry.getImageHeaderParsers(),
                        context.resources.displayMetrics,
                        glide.bitmapPool,
                        glide.arrayPool
                    ), glide.arrayPool
                )
            )
        )
        registry.prepend(
            File::class.java,
            GifDrawable::class.java,
            EncryptedGifCacheDecoder(
                secretProvider,
                StreamGifDecoder(
                    registry.getImageHeaderParsers(),
                    ByteBufferGifDecoder(
                        context,
                        registry.getImageHeaderParsers(),
                        glide.bitmapPool,
                        glide.arrayPool
                    ),
                    glide.arrayPool
                )
            )
        )

        registry.prepend(
            Bitmap::class.java,
            EncryptedBitmapResourceEncoder(secretProvider)
        )
        registry.prepend(
            GifDrawable::class.java,
            EncryptedGifDrawableResourceEncoder(secretProvider)
        )

        registry.append(
            RemoteFile::class.java, InputStream::class.java, RemoteFileLoader.Factory(
                remoteFileLoader
            )
        )
        registry.append(
            DecryptableUri::class.java,
            InputStream::class.java,
            DecryptableStreamUriLoader.Factory(context)
        )
        registry.append(
            AttachmentModel::class.java,
            InputStream::class.java,
            AttachmentStreamUriLoader.Factory()
        )
        registry.append(
            ChunkedImageUrl::class.java,
            InputStream::class.java,
            ChunkedImageUrlLoader.Factory()
        )
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory()
        )
    }
}