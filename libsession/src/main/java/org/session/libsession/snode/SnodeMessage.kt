package org.session.libsession.snode

data class SnodeMessage(
    /**
     * The hex encoded public key of the recipient.
     */
    val recipient: String,
    /**
     * The base64 encoded content of the message.
     */
    val data: String,
    /**
     * The time to live for the message in milliseconds.
     */
    val ttl: Long,
    /**
     * When the proof of work was calculated.
     *
     * **Note:** Expressed as milliseconds since 00:00:00 UTC on 1 January 1970.
     */
    val timestamp: Long
) {
    internal constructor(): this("", "", -1, -1)

    internal fun toJSON(): Map<String, String> {
        return mapOf(
            "pubkey" to recipient,
            "data" to data,
            "ttl" to ttl.toString(),
            "timestamp" to timestamp.toString(),
        )
    }

    companion object {
        const val CONFIG_TTL: Long = 30 * 24 * 60 * 60 * 1000L // 30 days
        const val DEFAULT_TTL: Long = 14 * 24 * 60 * 60 * 1000L // 14 days
    }
}
