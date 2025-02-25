package org.thoughtcrime.securesms.crypto

import android.content.Context
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import org.session.libsession.messaging.utilities.SodiumUtilities.sodium
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex

object KeyPairUtilities {

    fun generate(): KeyPairGenerationResult {
        // AHORA: generamos 32 bytes (256 bits) en lugar de 16
        val seed = sodium.randomBytesBuf(32)
        try {
            return generate(seed)
        } catch (exception: Exception) {
            return generate()
        }
    }

    fun generate(seed: ByteArray): KeyPairGenerationResult {
        // Se elimina la concatenación de 16 bytes en cero, usando directamente los 32 bytes
        val ed25519KeyPair = sodium.cryptoSignSeedKeypair(seed)
        val sodiumX25519KeyPair = sodium.convertKeyPairEd25519ToCurve25519(ed25519KeyPair)
        val x25519KeyPair = ECKeyPair(
            DjbECPublicKey(sodiumX25519KeyPair.publicKey.asBytes),
            DjbECPrivateKey(sodiumX25519KeyPair.secretKey.asBytes)
        )
        return KeyPairGenerationResult(seed, ed25519KeyPair, x25519KeyPair)
    }

    fun store(context: Context, seed: ByteArray, ed25519KeyPair: KeyPair, x25519KeyPair: ECKeyPair) {
        IdentityKeyUtil.save(context, IdentityKeyUtil.LOKI_SEED, Hex.toStringCondensed(seed))
        IdentityKeyUtil.save(
            context,
            IdentityKeyUtil.IDENTITY_PUBLIC_KEY_PREF,
            Base64.encodeBytes(x25519KeyPair.publicKey.serialize())
        )
        IdentityKeyUtil.save(
            context,
            IdentityKeyUtil.IDENTITY_PRIVATE_KEY_PREF,
            Base64.encodeBytes(x25519KeyPair.privateKey.serialize())
        )
        IdentityKeyUtil.save(
            context,
            IdentityKeyUtil.ED25519_PUBLIC_KEY,
            Base64.encodeBytes(ed25519KeyPair.publicKey.asBytes)
        )
        IdentityKeyUtil.save(
            context,
            IdentityKeyUtil.ED25519_SECRET_KEY,
            Base64.encodeBytes(ed25519KeyPair.secretKey.asBytes)
        )
    }

    fun hasV2KeyPair(context: Context): Boolean {
        return (IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_SECRET_KEY) != null)
    }

    fun getUserED25519KeyPair(context: Context): KeyPair? {
        val base64EncodedED25519PublicKey = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_PUBLIC_KEY) ?: return null
        val base64EncodedED25519SecretKey = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_SECRET_KEY) ?: return null

        val ed25519PublicKey = Key.fromBytes(Base64.decode(base64EncodedED25519PublicKey))
        val ed25519SecretKey = Key.fromBytes(Base64.decode(base64EncodedED25519SecretKey))
        return KeyPair(ed25519PublicKey, ed25519SecretKey)
    }

    data class KeyPairGenerationResult(
        val seed: ByteArray,
        val ed25519KeyPair: KeyPair,
        val x25519KeyPair: ECKeyPair
    )
}
