package org.thoughtcrime.securesms.glide

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.attachments.EmbeddedMetadataCodec
import org.thoughtcrime.securesms.attachments.FileMetadata
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime

class EncryptedFileCodecTest {

    @Test
    fun `encode and decode works`() {
        val codec = EmbeddedMetadataCodec(Json {})

        // Create a sample metadata object
        val expectMeta = FileMetadata(
            expiryTime = System.currentTimeMillis().millsToInstant(), // Should not use Instant.now() directly as it's microseconds precision
        )

        val expectContent = "Hello, World!".toByteArray()

        // Encode the metadata to a temporary file
        val tempFile = File.createTempFile("encrypted-file", ".tmp")
        tempFile.outputStream().use { outputStream ->
            codec.encodeToStream(expectMeta, outputStream)
            outputStream.write(expectContent)
        }

        // Decode the metadata from the temporary file
        val (actualMeta, actualBytes) = tempFile.inputStream().use {
            codec.decodeFromStream(it) to it.readAllBytes()
        }

        // Verify the metadata and content
        assertEquals(expectMeta, actualMeta)
        assertArrayEquals(expectContent, actualBytes)

        tempFile.delete()
    }
}