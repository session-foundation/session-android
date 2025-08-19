package org.thoughtcrime.securesms.coil

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.StaticImageDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class CoilModule {
    @Provides
    fun provideImageLoader(
        @ApplicationContext context: Context,
        factory: RemoteFileFetcher.CoilFetcherFactory
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .diskCache(null)
            .memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            )
            .components {
                add(RemoteFileKeyer())
                add(factory)
                add(GifDecoder.Factory())
                add(BitmapFactoryDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    add(StaticImageDecoder.Factory())
                }
            }
            .build()
    }
}