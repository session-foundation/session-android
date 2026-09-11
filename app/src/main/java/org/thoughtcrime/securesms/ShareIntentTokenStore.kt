package org.thoughtcrime.securesms

import org.session.libsession.utilities.Address
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issues opaque tokens that mark a share Intent as one this app built itself, and carries the
 * conversation such an Intent should open.
 *
 * `ShareActivity` is exported, so the Intent it receives is composed by whichever app invoked the
 * share sheet. A token stands in for the destination because it means nothing outside this process,
 * where the table that resolves it lives.
 */
@Singleton
class ShareIntentTokenStore @Inject constructor() {

    /** Present only for a token this store issued. A null [address] means "no destination chosen". */
    class Minted(val address: Address?)

    private val random = SecureRandom()

    private val issued = LinkedHashMap<String, Minted>()

    @Synchronized
    fun mint(address: Address? = null): String {
        // A chooser refresh mints one token per conversation, so retention is capped rather than
        // left to grow with however many times the share sheet has been opened this process.
        while (issued.size >= MAX_RETAINED) {
            issued.remove(issued.keys.first())
        }

        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

        issued[token] = Minted(address)
        return token
    }

    /**
     * Resolution deliberately does not retire the token: one user action can create `ShareActivity`
     * twice - once before app lock routes it away, once from the Intent the lock screen replays -
     * and each instance resolves the Intent independently.
     */
    @Synchronized
    fun resolve(token: String?): Minted? = token?.let(issued::get)

    private companion object {
        private const val TOKEN_BYTES = 32
        private const val MAX_RETAINED = 512
    }
}
