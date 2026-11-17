package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Signs and verifies with the JDK's Ed25519, because libsession's native ED25519 can't be loaded in
 * a JVM unit test. The signed message mirrors the storage server's `delete` response:
 * `swarm pubkey hex || requested hashes || deleted hashes`.
 */
class DeleteMessageApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val swarmPubKeyHex = "05" + "ab".repeat(32)
    private val requestedHashes = listOf("hashA", "hashB")

    private class TestSnode {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        // The JDK's X.509 encoding of an Ed25519 key is a fixed 12-byte header followed by the raw key.
        val pubKeyHex: String = Hex.toStringCondensed(keyPair.public.encoded.copyOfRange(12, 44))

        fun sign(vararg parts: Iterable<String>): String {
            val message = parts.asSequence().flatten().joinToString(separator = "").toByteArray()
            return Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(message)
                Base64.encodeBytes(sign())
            }
        }
    }

    private fun jdkVerify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean {
        val key = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(ED25519_X509_HEADER + publicKey))
        return Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    }

    private fun parse(body: JsonElement): DeleteMessageApi.SuccessResponse =
        DeleteMessageApi.parseSuccessResponse(
            json = json,
            body = body,
            requestedMessageHashes = requestedHashes,
            swarmPubKeyHex = swarmPubKeyHex,
            verifySignature = ::jdkVerify,
        )

    private fun response(vararg nodes: Pair<String, JsonElement>): JsonElement = buildJsonObject {
        putJsonObject("swarm") {
            nodes.forEach { (pubKey, state) -> put(pubKey, state) }
        }
        put("t", 1_700_000_000_000L)
    }

    private fun deletedState(deleted: List<String>, signature: String?): JsonElement = buildJsonObject {
        put("deleted", buildJsonArray { deleted.forEach { add(JsonPrimitive(it)) } })
        if (signature != null) put("signature", signature)
    }

    @Test
    fun `validly signed empty deleted list counts as success`() {
        val nodes = List(3) { TestSnode() }

        val result = parse(response(*nodes.map { node ->
            node.pubKeyHex to deletedState(
                deleted = emptyList(),
                signature = node.sign(listOf(swarmPubKeyHex), requestedHashes)
            )
        }.toTypedArray()))

        assertEquals(3, result.numSnodeSuccessDeleted)
        assertEquals(3, result.numSnodeTotal)
    }

    @Test
    fun `validly signed non-empty deleted list counts as success`() {
        val node = TestSnode()
        val deleted = listOf("hashA")

        val result = parse(response(
            node.pubKeyHex to deletedState(
                deleted = deleted,
                signature = node.sign(listOf(swarmPubKeyHex), requestedHashes, deleted)
            )
        ))

        assertEquals(1, result.numSnodeSuccessDeleted)
    }

    @Test
    fun `signature over a different deleted list does not count`() {
        val honest = TestSnode()
        val mismatched = TestSnode()

        val result = parse(response(
            honest.pubKeyHex to deletedState(
                deleted = emptyList(),
                signature = honest.sign(listOf(swarmPubKeyHex), requestedHashes)
            ),
            // Claims nothing was deleted, but signed as if hashA had been.
            mismatched.pubKeyHex to deletedState(
                deleted = emptyList(),
                signature = mismatched.sign(listOf(swarmPubKeyHex), requestedHashes, listOf("hashA"))
            ),
        ))

        assertEquals(1, result.numSnodeSuccessDeleted)
        assertEquals(2, result.numSnodeTotal)
    }

    @Test
    fun `empty deleted list without a valid signature fails`() {
        val signedByOther = TestSnode()
        val unsigned = TestSnode()
        val claimed = TestSnode()

        assertThrows(IllegalStateException::class.java) {
            parse(response(
                unsigned.pubKeyHex to deletedState(deleted = emptyList(), signature = null),
                // A valid signature, but from a key other than the node it is reported for.
                claimed.pubKeyHex to deletedState(
                    deleted = emptyList(),
                    signature = signedByOther.sign(listOf(swarmPubKeyHex), requestedHashes)
                ),
            ))
        }
    }

    @Test
    fun `failed snodes do not count`() {
        val ok = TestSnode()
        val failed = TestSnode()

        val result = parse(response(
            ok.pubKeyHex to deletedState(
                deleted = emptyList(),
                signature = ok.sign(listOf(swarmPubKeyHex), requestedHashes)
            ),
            failed.pubKeyHex to buildJsonObject {
                put("failed", true)
                put("code", 503)
                put("reason", "unavailable")
            },
        ))

        assertEquals(1, result.numSnodeSuccessDeleted)
        assertEquals(2, result.numSnodeTotal)
    }

    private companion object {
        val ED25519_X509_HEADER = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
    }
}
