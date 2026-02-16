package org.thoughtcrime.securesms.attachments

import android.app.Application
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.encrypt.EncryptionStream
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * A class to handle writing encrypted file content and metadata to a local file.
 *
 * It uses [EmbeddedMetadataCodec] to encode the metadata and file content, and
 * [EncryptionStream] to encrypt the file content.
 *
 * This class is paired with [LocalEncryptedFileInputStream] for reading encrypted files.
 */
class LocalEncryptedFileOutputStream @AssistedInject constructor(
    @Assisted file: File,
    @Assisted meta: FileMetadata,
    codec: EmbeddedMetadataCodec,
    application: Application
): OutputStream() {
    private val outputStream = EncryptionStream(
        out = FileOutputStream(file.also {
            it.parentFile!!.mkdirs() // Make sure the parent directory exists
        }),
        key = AttachmentSecretProvider.getInstance(application).orCreateAttachmentSecret.modernKey,
    ).also {
        codec.encodeToStream(meta, it)
    }
    override fun write(b: Int) {
        outputStream.write(b)
    }

    override fun write(b: ByteArray) {
        outputStream.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        outputStream.write(b, off, len)
    }

    override fun flush() {
        super.flush()
        outputStream.flush()
    }

    override fun close() {
        super.close()
        outputStream.close()
    }

    @AssistedFactory
    interface Factory {
        fun create(file: File, meta: FileMetadata): LocalEncryptedFileOutputStream
    }
}