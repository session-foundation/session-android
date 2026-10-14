package org.session.libsignal.streams

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.session.libsignal.utilities.ByteArraySlice.Companion.view

/**
 * Content encrypted the legacy way stays on the file server indefinitely, so this format has to keep
 * decrypting long after nothing writes it. The vectors below were produced externally rather than by
 * a round trip, so they still describe the wire format now that the encrypting half is gone.
 */
class AttachmentCipherInputStreamTest {

    /** 32-byte AES key ‖ 32-byte HMAC key. */
    private val key = ByteArray(32) { 0x11 } + ByteArray(32) { 0x22 }

    /** IV ‖ AES-256-CBC(PKCS7) ‖ HMAC-SHA256(IV ‖ ciphertext), over a 541-byte zero-padded plaintext. */
    private val ciphertext = ("333333333333333333333333333333330ee1ed4f89c70232caed52b8765365ff" +
        "e904557d3c61038c97bdb5bee3189fc6f53aac97422dc92d8693c0a72b3aa362" +
        "01d1bd3a6dd6164a09154ae87aa95888066a80449e211fc64a69c04b0f9ddce0" +
        "2f144fbea3a731ded616d0436b2fd6ec908f89ebf85d15bad69bdaa66827f0b3" +
        "484b7425d383f7136e3c911881731c043863f49f5ada6f85f8fa456efd08a2db" +
        "8c7b510cb04a225035db36e21660f5274939dc09639ffd30cc36ac9fc8776761" +
        "5a93ab017c028a8bdf2dd7ef48c245b846c7cd4942866d77b056a2a59924d426" +
        "4d1e1d23b8c5ed598f23ada8a905c5579383581203db2c5f60c434551992162f" +
        "b9f36105a91515c5472105c64f3c111a16c745bbca3e810460319b3124c430ca" +
        "3d87f4fd9400502cd62d7e782ee5eebf4656f4acd84a9ae964e0f40804dea71d" +
        "675eb620a2659094b52a3bdf9b36c8ac66ffcbbb129749e5394eabf4b8708249" +
        "12cf7966797621b0ca073baf66d500d5589649b89d5298855471178c724907d2" +
        "5e5812ea7cbf4d9fdf19319eeba03b491cf2857a388ef861ea11e83876d43751" +
        "fbbc80aaf205e5d937e9dcc046f9aaf3ee56b6a627a5a474e1ac5cde5a12e52a" +
        "fd23fcdadeb591aa06f374bd84edf8a5d61151df711ed8fbded86f0b23c28ef4" +
        "414b99d422c4bf95f30f1f18b274c42d9e61a48f63a754d100c7bf64fc88f469" +
        "407560f167919c9d5f10d5a343dff87e753ab281e57073fce95481d2b8c4da0e" +
        "5f0d13c67d802c1733c232e3d520f38a4f9d5a43c6ce60843288322b883d7d24" +
        "796efd75c676cb02a5b6148ea737534d").hexToBytes()

    private val digest =
        "ee65e6417b452d017a4d8e2ce984883c1f7dc8d920d7551f0e2d2af7606bd9a9".hexToBytes()

    private val plaintext = "legacy attachment payload".toByteArray()

    @Test
    fun `decrypts a legacy attachment`() {
        val decrypted = AttachmentCipherInputStream
            .createForAttachment(ciphertext.view(), key, digest)
            .use { it.readBytes() }

        // this overload does not trim, so the sender's zero padding is still on the end
        assertArrayEquals(plaintext, decrypted.copyOfRange(0, plaintext.size))
        assertArrayEquals(ByteArray(decrypted.size - plaintext.size), decrypted.copyOfRange(plaintext.size, decrypted.size))
    }

    @Test
    fun `rejects a tampered ciphertext`() {
        val tampered = ciphertext.copyOf().also { it[20] = (it[20] + 1).toByte() }

        assertThrows(Exception::class.java) {
            AttachmentCipherInputStream.createForAttachment(tampered.view(), key, digest)
        }
    }

    @Test
    fun `rejects a wrong key`() {
        val wrongKey = ByteArray(32) { 0x11 } + ByteArray(32) { 0x23 }

        assertThrows(Exception::class.java) {
            AttachmentCipherInputStream.createForAttachment(ciphertext.view(), wrongKey, digest)
        }
    }

    @Test
    fun `rejects a missing digest`() {
        assertThrows(Exception::class.java) {
            AttachmentCipherInputStream.createForAttachment(ciphertext.view(), key, null)
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
