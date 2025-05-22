package org.thoughtcrime.securesms.crypto

import android.content.Context
import network.loki.messenger.libsession_util.Curve25519
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import java.security.SecureRandom

object KeyPairUtilities {

    fun generate(): KeyPairGenerationResult {
        val seed = ByteArray(16).also {
            SecureRandom().nextBytes(it)
        }

        return generate(seed)
    }

    fun generate(seed: ByteArray): KeyPairGenerationResult {
        val paddedSeed = seed + ByteArray(16)
        val ed25519KeyPair = ED25519.generate(paddedSeed)
        val x25519KeyPair = Curve25519.fromED25519(ed25519KeyPair)
        return KeyPairGenerationResult(seed, ed25519KeyPair,
            ECKeyPair(DjbECPublicKey(x25519KeyPair.pubKey.data), DjbECPrivateKey(x25519KeyPair.secretKey.data)))
    }

    fun store(context: Context, seed: ByteArray, ed25519KeyPair: KeyPair, x25519KeyPair: ECKeyPair) {
        IdentityKeyUtil.save(context, IdentityKeyUtil.LOKI_SEED, Hex.toStringCondensed(seed))
        IdentityKeyUtil.save(context, IdentityKeyUtil.IDENTITY_PUBLIC_KEY_PREF, Base64.encodeBytes(x25519KeyPair.publicKey.serialize()))
        IdentityKeyUtil.save(context, IdentityKeyUtil.IDENTITY_PRIVATE_KEY_PREF, Base64.encodeBytes(x25519KeyPair.privateKey.serialize()))
        IdentityKeyUtil.save(context, IdentityKeyUtil.ED25519_PUBLIC_KEY, Base64.encodeBytes(ed25519KeyPair.pubKey.data))
        IdentityKeyUtil.save(context, IdentityKeyUtil.ED25519_SECRET_KEY, Base64.encodeBytes(ed25519KeyPair.secretKey.data))
    }

    fun getUserED25519KeyPair(context: Context): KeyPair? {
        val base64EncodedED25519PublicKey = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_PUBLIC_KEY) ?: return null
        val base64EncodedED25519SecretKey = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_SECRET_KEY) ?: return null
        return KeyPair(pubKey = Base64.decode(base64EncodedED25519PublicKey), secretKey = Base64.decode(base64EncodedED25519SecretKey))
    }

    data class KeyPairGenerationResult(
        val seed: ByteArray,
        val ed25519KeyPair: KeyPair,
        val x25519KeyPair: ECKeyPair
    )
}