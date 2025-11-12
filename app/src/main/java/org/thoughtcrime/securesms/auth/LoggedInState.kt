package org.thoughtcrime.securesms.auth

import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.Curve25519
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.pro.ProProof
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.utilities.serializable.BytesAsBase64Serializer
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import org.session.libsession.utilities.serializable.KeyPairAsArraySerializer
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.pro.api.ProProofSerializer
import java.security.SecureRandom
import java.time.Instant

@Serializable
data class LoggedInState(
    val seeded: Seeded,

    @Serializable(with = BytesAsBase64Serializer::class)
    val notificationKey: Bytes,

    val proState: ProState?,
) {
    init {
        check(notificationKey.data.size == NOTIFICATION_KEY_LENGTH) {
            "Notification key must be $NOTIFICATION_KEY_LENGTH bytes"
        }
    }

    val accountEd25519KeyPair: KeyPair get() = seeded.accountEd25519KeyPair
    val accountX25519KeyPair: KeyPair get() = seeded.accountX25519KeyPair
    val accountId: AccountId get() = seeded.accountId
    val proMasterPrivateKey: ByteArray get() = seeded.proMasterPrivateKey

    fun ensureValidProRotatingKey(now: Instant = Instant.now()): LoggedInState {
        if (proState == null || proState.isRotatingKeyExpired(now)) {
            return copy(
                proState = ProState(
                    rotatingKeyPair = Curve25519.generateKeyPair(),
                    rotatingKeyExpiry = now.plusSeconds(ROTATING_KEY_VALIDITY_HOURS * 3600),
                    proProof = proState?.proProof
                )
            )
        }

        return this
    }

    /**
     * Holds the account seed. Almost all account related keys are derived from this seed.
     */
    @Serializable
    data class Seeded(
        @Serializable(with = BytesAsBase64Serializer::class)
        val seed: Bytes
    ) {
        init {
            check(seed.data.size == SEED_LENGTH) {
                "Account seed must be $SEED_LENGTH bytes"
            }
        }

        val accountEd25519KeyPair: KeyPair by lazy(LazyThreadSafetyMode.NONE) {
            ED25519.generate(seed.data + ByteArray(16))
        }

        val accountX25519KeyPair: KeyPair by lazy(LazyThreadSafetyMode.NONE) {
            Curve25519.fromED25519(accountEd25519KeyPair)
        }

        val accountId: AccountId by lazy(LazyThreadSafetyMode.NONE) {
            AccountId(IdPrefix.STANDARD, accountX25519KeyPair.pubKey.data)
        }

        val proMasterPrivateKey: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
            ED25519.generateProPrivateKey(seed.data)
        }

        override fun toString(): String {
            return "Seeded(id=$accountId)"
        }
    }

    @Serializable
    data class ProState(
        @Serializable(with = KeyPairAsArraySerializer::class)
        val rotatingKeyPair: KeyPair,

        @Serializable(with = InstantAsMillisSerializer::class)
        val rotatingKeyExpiry: Instant,

        @Serializable(with = ProProofSerializer::class)
        val proProof: ProProof?,
    ) {
        override fun toString(): String {
            return "ProState(rotatingKeyPair=[REDACTED], rotatingKeyExpiry=$rotatingKeyExpiry, proProof=${if (proProof != null) "[REDACTED]" else "null"})"
        }

        fun isRotatingKeyExpired(now: Instant = Instant.now()): Boolean {
            return now.isAfter(rotatingKeyExpiry)
        }
    }

    override fun toString(): String {
        return "LoggedInState(accountId=$accountId, proState=$proState)"
    }

    companion object {
        private const val SEED_LENGTH = 16
        private const val NOTIFICATION_KEY_LENGTH = 32

        const val ROTATING_KEY_VALIDITY_HOURS = 12L

        fun generate(seed: ByteArray?): LoggedInState {
            return LoggedInState(
                seeded = Seeded(
                    seed = Bytes(seed ?: (ByteArray(SEED_LENGTH).apply {
                        SecureRandom().nextBytes(this)
                    }))
                ),
                notificationKey = Bytes(ByteArray(NOTIFICATION_KEY_LENGTH).apply {
                    SecureRandom().nextBytes(this)
                }),
                proState = null
            )
        }
    }
}