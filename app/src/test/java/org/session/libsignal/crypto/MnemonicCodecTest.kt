package org.session.libsignal.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class MnemonicCodecTest {

    private val codec = MnemonicCodec { File("src/main/assets/mnemonic/$it.txt").readText() }

    private val seed16Hex = "0123456789abcdef0123456789abcdef"
    private val seed32Hex = seed16Hex + "fedcba9876543210fedcba9876543210"

    @Test
    fun `13-word phrase for a 16-byte seed round trips`() {
        val phrase = codec.encode(seed16Hex)

        assertEquals(13, phrase.split(" ").size)
        assertEquals(seed16Hex, codec.decode(phrase))
    }

    @Test
    fun `25-word phrase for a 32-byte seed is refused as too long`() {
        val phrase = codec.encode(seed32Hex)
        assertEquals(25, phrase.split(" ").size)

        assertThrows(MnemonicCodec.DecodingError.InputTooLong::class.java) {
            codec.sanitizeAndDecodeAsByteArray(phrase)
        }
    }

    @Test
    fun `14-word phrase is refused as too long`() {
        val phrase = codec.encode(seed16Hex) + " " + codec.encode(seed16Hex).split(" ").first()

        assertThrows(MnemonicCodec.DecodingError.InputTooLong::class.java) {
            codec.decode(phrase)
        }
    }

    @Test
    fun `12-word phrase is refused as too short`() {
        val phrase = codec.encode(seed16Hex).split(" ").dropLast(1).joinToString(" ")

        assertThrows(MnemonicCodec.DecodingError.InputTooShort::class.java) {
            codec.decode(phrase)
        }
    }
}
