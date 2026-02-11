package org.thoughtcrime.securesms.mms

import com.bumptech.glide.load.Key
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class AttachmentStreamUriLoader : ModelLoader<AttachmentStreamUriLoader.AttachmentModel, InputStream> {

    override fun buildLoadData(
        attachmentModel: AttachmentModel,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(
            attachmentModel,
            AttachmentStreamLocalUriFetcher(
                attachment = attachmentModel.attachment,
                plaintextLength = attachmentModel.plaintextLength,
                key = attachmentModel.key,
                digest = attachmentModel.digest
            )
        )
    }

    override fun handles(attachmentModel: AttachmentModel): Boolean = true

    class Factory : ModelLoaderFactory<AttachmentModel, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<AttachmentModel, InputStream> {
            return AttachmentStreamUriLoader()
        }

        override fun teardown() = Unit
    }

    data class AttachmentModel(
        val attachment: File,
        val key: ByteArray,
        val plaintextLength: Long,
        val digest: ByteArray?,
    ) : Key {

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update(attachment.toString().toByteArray())
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as AttachmentModel

            if (plaintextLength != other.plaintextLength) return false
            if (attachment != other.attachment) return false
            if (!key.contentEquals(other.key)) return false
            if (!digest.contentEquals(other.digest)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = plaintextLength.hashCode()
            result = 31 * result + attachment.hashCode()
            result = 31 * result + key.contentHashCode()
            result = 31 * result + (digest?.contentHashCode() ?: 0)
            return result
        }
    }
}
