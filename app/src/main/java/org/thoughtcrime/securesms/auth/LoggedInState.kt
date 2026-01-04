package org.thoughtcrime.securesms.auth

import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.Curve25519
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsession.utilities.serializable.BytesAsBase64Serializer
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import java.security.SecureRandom

@Serializable
data class LoggedInState(
    val seeded: Seeded,

    @Serializable(with = BytesAsBase64Serializer::class)
    val notificationKey: Bytes,
) {
    init {
        check(notificationKey.data.size == NOTIFICATION_KEY_LENGTH) {
            "Notification key must be $NOTIFICATION_KEY_LENGTH bytes"
        }
    }

    val accountEd25519KeyPair: KeyPair get() = seeded.accountEd25519KeyPair
    val accountX25519KeyPair: KeyPair get() = seeded.accountX25519KeyPair
    val accountId: AccountId get() = seeded.accountId


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

        private val paddedSeed: ByteArray by lazy {
            seed.data + ByteArray(16)
        }

        val accountEd25519KeyPair: KeyPair by lazy {
            ED25519.generate(paddedSeed)
        }

        val accountX25519KeyPair: KeyPair by lazy {
            Curve25519.fromED25519(accountEd25519KeyPair)
        }

        val accountId: AccountId by lazy {
            AccountId(IdPrefix.STANDARD, accountX25519KeyPair.pubKey.data)
        }

        val proMasterPrivateKey: ByteArray by lazy {
            ED25519.generateProMasterKey(paddedSeed)
        }

        override fun toString(): String {
            return "Seeded(id=$accountId)"
        }
    }


    override fun toString(): String {
        return "LoggedInState(accountId=$accountId)"
    }

    companion object {
        private const val SEED_LENGTH = 16
        private const val NOTIFICATION_KEY_LENGTH = 32


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
            )
        }
    }
}