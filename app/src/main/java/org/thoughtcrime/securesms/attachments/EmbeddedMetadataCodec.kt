package org.thoughtcrime.securesms.attachments

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.Instant
import javax.inject.Inject

/**
 * Codec for reading and writing both file metadata and content on disk.
 *
 * You can safely extend the `EncryptedFileMeta` class to add more metadata fields in the future,
 * as long as you ensure that the metadata is serialized in a old version compatible way (such as
 * making them optional properties by default).
 */
class EmbeddedMetadataCodec @Inject constructor(
    private val json: Json
) {
    /**
     * Decodes the metadata from the input stream and returns it along with the remaining input stream.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodeFromStream(input: InputStream): FileMetadata {
        // First 4 bytes are the length of the metadata
        val lengthBytes = ByteArray(4)
        check(input.read(lengthBytes) == lengthBytes.size) {
            "Failed to read the length of the metadata from the input stream."
        }

        // Convert the length bytes to an integer as BE
        val length: Int = ByteBuffer.wrap(lengthBytes).int

        // Deserialize the metadata
        val metaBytes = ByteArray(length)
        check(input.read(metaBytes) == metaBytes.size) {
            "Failed to read the metadata from the input stream. Expected $length bytes, but got less."
        }

        return json.decodeFromStream<FileMetadata>(ByteArrayInputStream(metaBytes))
    }

    /**
     * Encodes the metadata into the output stream.
     */
    fun encodeToStream(meta: FileMetadata, outStream: OutputStream) {
        // Encode the metadata as JSON
        val metaJsonBytes = json.encodeToString(meta).toByteArray(Charsets.UTF_8)
        val lengthBytes = ByteBuffer.allocate(4).putInt(metaJsonBytes.size).array()

        // Write the length of the metadata
        outStream.write(lengthBytes)
        // Write the metadata JSON
        outStream.write(metaJsonBytes)
    }
}


/**
 * Metadata for an encrypted file.
 *
 * Note: you should not change this class without considering backward compatibility,
 * especially if you add non-nullable properties, the old metadata won't be able to be deserialized.
 *
 * As a rule of thumb, you can add new optional properties, but avoid changing existing ones(even renaming).
 */
@Serializable
data class FileMetadata(
    @Serializable(with = InstantAsMillisSerializer::class)
    val expiryTime: Instant? = null,
    val hasPermanentDownloadError: Boolean = false,
)