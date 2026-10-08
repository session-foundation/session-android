package org.thoughtcrime.securesms.attachments

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.loki.messenger.libsession_util.encrypt.Attachments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * The attachment format is shared with iOS and Desktop, and all three reach it through libSession, so
 * the thing worth asserting is that this platform's binding reproduces the exact bytes rather than
 * merely round-tripping with itself. The vectors were generated from libSession's C API on the same
 * source the other two clients build, so a failure here means the platforms have diverged.
 *
 * This has to be an instrumented test: no JVM unit test in this project can load `libsession_util.so`.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStreamEncryptionVectorTest {

    private val seed = ByteArray(32) { it.toByte() }
    private val plaintext = "cross client vector".toByteArray()

    private val attachmentKey =
        "2227f0271670f5a6873d65501c3e3362b34aab5da0aec3b986accb7aa5a1643a".hexToBytes()
    private val attachmentCiphertextSha256 =
        "bbc8f2c708e6e3e97ca67bd52e00ff081e6294461bb62d635a9b4048d8ec78c0".hexToBytes()

    private val profilePicKey =
        "09edaf4b7b48b405c1c01f0c3347fc68e65adea0d9696d24e80411a0cced90cb".hexToBytes()
    private val profilePicCiphertextSha256 =
        "e02d1ee8ee6289b8297c8799c43bd4c2250ac95a346abf0f5b93e191e57d1503".hexToBytes()

    private fun encrypt(domain: Attachments.Domain): Pair<ByteArray, ByteArray> {
        val cipherOut = ByteArray(Attachments.encryptedSize(plaintext.size.toLong()).toInt())
        val key = Attachments.encryptBytes(
            seed = seed,
            plaintextIn = plaintext,
            cipherOut = cipherOut,
            domain = domain,
        )
        return key to cipherOut
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun attachmentDomainMatchesTheSharedVector() {
        val (key, ciphertext) = encrypt(Attachments.Domain.Attachment)

        assertEquals(4096, ciphertext.size)
        assertEquals(0x53.toByte(), ciphertext[0])          // the 'S' identifier
        assertArrayEquals(attachmentKey, key)
        assertArrayEquals(attachmentCiphertextSha256, sha256(ciphertext))
    }

    @Test
    fun profilePicDomainMatchesTheSharedVector() {
        val (key, ciphertext) = encrypt(Attachments.Domain.ProfilePic)

        assertEquals(0x53.toByte(), ciphertext[0])
        assertArrayEquals(profilePicKey, key)
        assertArrayEquals(profilePicCiphertextSha256, sha256(ciphertext))
    }

    @Test
    fun theTwoDomainsProduceUnrelatedOutput() {
        val (attachmentK, attachmentC) = encrypt(Attachments.Domain.Attachment)
        val (profileK, profileC) = encrypt(Attachments.Domain.ProfilePic)

        assertEquals(false, attachmentK.contentEquals(profileK))
        assertEquals(false, attachmentC.contentEquals(profileC))
    }

    @Test
    fun decryptsWhatTheOtherPlatformsProduce() {
        for (domain in listOf(Attachments.Domain.Attachment, Attachments.Domain.ProfilePic)) {
            val (key, ciphertext) = encrypt(domain)
            val plainOut = ByteArray(
                requireNotNull(Attachments.decryptedMaxSizeOrNull(ciphertext.size.toLong())).toInt()
            )
            val size = Attachments.decryptBytes(key, ciphertext, plainOut).toInt()

            assertArrayEquals(plaintext, plainOut.copyOfRange(0, size))
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
