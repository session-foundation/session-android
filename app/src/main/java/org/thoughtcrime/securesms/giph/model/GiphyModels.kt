package org.thoughtcrime.securesms.giph.model

import kotlinx.serialization.Serializable

@Serializable
data class GiphyResponse(
    val data: List<GiphyImage> = emptyList()
)

@Serializable
data class GiphyImage(
    val images: ImageTypes? = null
) {
    fun getGifUrl(): String? = images?.downsized?.url
    fun getGifSize(): Long = images?.downsized?.size() ?: 0L

    fun getGifMmsUrl(): String? = images?.fixed_height_downsampled?.url
    fun getMmsGifSize(): Long = images?.fixed_height_downsampled?.size() ?: 0L

    fun getGifAspectRatio(): Float {
        val w = images?.downsized?.width()?.toFloat() ?: return 1f
        val h = images?.downsized?.height()?.toFloat() ?: return 1f
        return if (h == 0f) 1f else (w / h)
    }

    fun getGifWidth(): Int = images?.downsized?.width() ?: 0
    fun getGifHeight(): Int = images?.downsized?.height() ?: 0

    fun getStillUrl(): String? = images?.downsized_still?.url
    fun getStillSize(): Long = images?.downsized_still?.size() ?: 0L
}

@Serializable
data class ImageTypes(
    val fixed_height: ImageData? = null,
    val fixed_height_still: ImageData? = null,
    val fixed_height_downsampled: ImageData? = null,
    val fixed_width: ImageData? = null,
    val fixed_width_still: ImageData? = null,
    val fixed_width_downsampled: ImageData? = null,
    val fixed_width_small: ImageData? = null,
    val downsized_medium: ImageData? = null,
    val downsized: ImageData? = null,
    val downsized_still: ImageData? = null
)

@Serializable
data class ImageData(
    val url: String? = null,
    val width: String? = null,
    val height: String? = null,
    val size: String? = null,
    val mp4: String? = null,
    val webp: String? = null,
    val mp4_size: String? = null,
    val webp_size: String? = null,
) {
    fun width(): Int = width?.toIntOrNull() ?: 0
    fun height(): Int = height?.toIntOrNull() ?: 0
    fun size(): Long = size?.toLongOrNull() ?: 0L
}
